#!/usr/bin/env bash
# 跨机混沌工具（Phase 16）：容器网络故障注入（tc netem + iptables）。
# 用法：
#   ./chaos-netem.sh <container> latency <ms>
#   ./chaos-netem.sh <container> loss <percent>
#   ./chaos-netem.sh <container> partition <peer-container>
#   ./chaos-netem.sh <container> heal
set -euo pipefail

CONTAINER="$1"
ACTION="$2"

case "$ACTION" in
  latency)
    docker exec "$CONTAINER" tc qdisc replace dev eth0 root netem delay "${3}ms"
    echo "latency ${3}ms -> $CONTAINER"
    ;;
  loss)
    docker exec "$CONTAINER" tc qdisc replace dev eth0 root netem loss "${3}%"
    echo "loss ${3}% -> $CONTAINER"
    ;;
  partition)
    PEER_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$3")
    docker exec "$CONTAINER" sh -c "iptables -A OUTPUT -d $PEER_IP -j DROP; iptables -A INPUT -s $PEER_IP -j DROP"
    echo "partition $CONTAINER <-> $3"
    ;;
  heal)
    docker exec "$CONTAINER" tc qdisc del dev eth0 root 2>/dev/null || true
    docker exec "$CONTAINER" iptables -F OUTPUT 2>/dev/null || true
    docker exec "$CONTAINER" iptables -F INPUT 2>/dev/null || true
    echo "healed $CONTAINER"
    ;;
  disk-slow)
    # 尽力而为：降低容器 IO 优先级（需容器内 ionice 与权限）
    docker exec "$CONTAINER" sh -c "ionice -c 3 -p \$(pidof java) 2>/dev/null || echo 'ionice unavailable'"
    ;;
  kill)
    docker kill -s 9 "$CONTAINER"
    echo "killed -9 $CONTAINER"
    ;;
  *)
    echo "unknown action $ACTION" >&2
    exit 1
    ;;
esac
