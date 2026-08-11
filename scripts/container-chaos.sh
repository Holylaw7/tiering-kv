#!/usr/bin/env bash
set -euo pipefail

# 容器混沌（ADR-0100）：kill coordinator/participant/metadata + 分区恢复。
# 用法：scripts/container-chaos.sh up|kill-coordinator|kill-participant|kill-meta|partition|down

COMPOSE="docker compose -f deploy/docker-compose.transaction.yml"

case "${1:-up}" in
  up)
    $COMPOSE build
    $COMPOSE up -d --wait
    ;;
  kill-coordinator)
    docker kill txn-coordinator
    sleep 2
    docker start txn-coordinator
    ;;
  kill-participant)
    docker kill txn-participant-a
    sleep 2
    docker start txn-participant-a
    ;;
  kill-meta)
    docker kill txn-meta
    sleep 2
    docker start txn-meta
    ;;
  partition)
    # 在 coordinator 上隔离 metadata 2 秒（tc netem）
    docker exec txn-coordinator sh -c \
      "tc qdisc add dev eth0 root netem loss 100% 2>/dev/null || true"
    sleep 2
    docker exec txn-coordinator sh -c \
      "tc qdisc del dev eth0 root 2>/dev/null || true"
    ;;
  down)
    $COMPOSE down -v
    ;;
  *)
    echo "unknown command: ${1}" >&2
    exit 1
    ;;
esac
