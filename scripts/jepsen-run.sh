#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Jepsen 式外部化验证（ADR-0322）：
# 1) 容器级故障注入（kill-coordinator / kill-participant / kill-meta / partition）
# 2) VerificationHarness 独立进程线性一致性回归（每次注入后）
# 用法：scripts/jepsen-run.sh [run|cleanup]
COMPOSE="docker compose -f deploy/docker-compose.transaction.yml"

case "${1:-run}" in
  run)
    mkdir -p target
    REPORT=target/jepsen-report.txt
    : > "$REPORT"
    mvn -q -DskipTests compile

    echo "== compose up ==" | tee -a "$REPORT"
    $COMPOSE up -d --wait >> "$REPORT" 2>&1

    for fault in kill-coordinator kill-participant kill-meta partition; do
      echo "== fault injection: $fault ==" | tee -a "$REPORT"
      scripts/container-chaos.sh "$fault" >> "$REPORT" 2>&1 || true
      $COMPOSE up -d --wait >> "$REPORT" 2>&1 || true
      if java -cp target/classes \
          io.tieringkv.distributed.harness.VerificationHarness \
          8 300 >> "$REPORT" 2>&1; then
        echo "linearizability: OK after $fault" | tee -a "$REPORT"
      else
        echo "linearizability: FAILED after $fault" | tee -a "$REPORT"
        exit 1
      fi
    done

    echo "jepsen report: $REPORT"
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
