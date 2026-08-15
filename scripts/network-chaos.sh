#!/usr/bin/env bash
set -euo pipefail

# 真实网络混沌（ADR-0343 / TD-035）：对事务栈后端容器 eth0 应用/恢复
# tc netem。镜像需安装 iproute2（deploy/Dockerfile）；应用后强制校验
# qdisc 含 netem，未生效即失败（禁止静默降级）。
# 用法：scripts/network-chaos.sh delay 100ms | loss 10% | partition |
#       recover | show

BACKEND_CONTAINERS="txn-coordinator txn-participant-a txn-participant-b txn-meta"

tc_show() {
  for c in $BACKEND_CONTAINERS; do
    echo "[$c]"
    docker exec "$c" sh -c "tc qdisc show dev eth0 2>/dev/null || true"
  done
}

apply_netem() {
  local rule=$1
  for c in $BACKEND_CONTAINERS; do
    docker exec "$c" sh -c \
      "tc qdisc del dev eth0 root 2>/dev/null || true; tc qdisc add dev eth0 root netem $rule"
  done
  # 校验：任一后端容器 qdisc 未生效即失败
  local verified=0
  for c in $BACKEND_CONTAINERS; do
    if docker exec "$c" sh -c "tc qdisc show dev eth0" \
        | grep -q "netem"; then
      verified=$((verified + 1))
    fi
  done
  if [ "$verified" -ne 4 ]; then
    echo "netem not applied on all backend containers (verified=$verified)" >&2
    exit 1
  fi
  echo "TIERINGKV_NETEM_APPLIED=true"
}

case "${1:-recover}" in
  delay)
    apply_netem "delay ${2:-100ms}"
    ;;
  loss)
    apply_netem "loss ${2:-10%}"
    ;;
  partition)
    apply_netem "loss 100%"
    ;;
  recover)
    for c in $BACKEND_CONTAINERS; do
      docker exec "$c" sh -c "tc qdisc del dev eth0 root 2>/dev/null || true"
    done
    echo "TIERINGKV_NETEM_RECOVERED=true"
    ;;
  show)
    tc_show
    ;;
  *)
    echo "unknown command: ${1}" >&2
    exit 1
    ;;
esac
