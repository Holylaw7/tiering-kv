#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 多集群故障切换演练（ADR-0322 M4 增强）：
# 真实 RESP 客户端链路：primary 运行 → 故障注入（kill coordinator）→
# 观察故障窗口 → 恢复 → 真实客户端线性一致性冒烟回切。
# 用法：scripts/multi-cluster-failover.sh [run|cleanup]
COMPOSE="docker compose -f deploy/docker-compose.transaction.yml"

run_harness() {
  java -cp target/classes \
    io.tieringkv.distributed.harness.VerificationHarness \
    "$1" "$2" resp 127.0.0.1 6379
}

case "${1:-run}" in
  run)
    mkdir -p target
    REPORT=target/multi-cluster-failover-report.txt
    : > "$REPORT"
    mvn -q -DskipTests compile

    echo "== primary cluster up ==" | tee -a "$REPORT"
    $COMPOSE up -d --wait >> "$REPORT" 2>&1

    echo "== smoke before failover ==" | tee -a "$REPORT"
    run_harness 4 100 >> "$REPORT" 2>&1 \
      || { echo "pre-smoke failed" | tee -a "$REPORT"; exit 1; }

    echo "== inject: kill coordinator (primary loss) ==" \
      | tee -a "$REPORT"
    scripts/container-chaos.sh kill-coordinator >> "$REPORT" 2>&1 \
      || true

    echo "== verify outage window (failure tolerated) ==" \
      | tee -a "$REPORT"
    run_harness 4 50 >> "$REPORT" 2>&1 \
      || echo "outage observed (expected)" | tee -a "$REPORT"

    echo "== recover primary ==" | tee -a "$REPORT"
    $COMPOSE up -d --wait >> "$REPORT" 2>&1

    echo "== smoke after recovery (failback) ==" | tee -a "$REPORT"
    run_harness 8 300 >> "$REPORT" 2>&1 \
      || { echo "post-recovery smoke failed" | tee -a "$REPORT"; exit 1; }

    echo "failover drill report: $REPORT"
    cat "$REPORT"
    ;;
  cleanup)
    $COMPOSE down -v
    ;;
  *)
    echo "unknown command: ${1} (run|cleanup)" >&2
    exit 2
    ;;
esac
