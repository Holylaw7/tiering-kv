#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Jepsen 式外部化验证（ADR-0322）：
# 1) 容器级故障注入（kill-coordinator / kill-participant / kill-meta / partition）
# 2) VerificationHarness 独立进程线性一致性回归（每次注入后）
# 用法：scripts/jepsen-run.sh [run|cleanup]
COMPOSE="docker compose -f deploy/docker-compose.transaction.yml"

retry_n() {
  local n="$1"
  shift
  for i in $(seq 1 "$n"); do
    if "$@" >> "$REPORT" 2>&1; then
      return 0
    fi
    echo "attempt ${i}/${n} failed, retrying in 10s... ($*)" \
      | tee -a "$REPORT"
    sleep 10
  done
  echo "failed after ${n} attempts: $*" | tee -a "$REPORT"
  return 1
}

case "${1:-run}" in
  run)
    mkdir -p target
    REPORT=target/jepsen-report.txt
    : > "$REPORT"
    # GitHub runner 偶发 Maven Central 下载 / BuildKit 瞬时故障
    # （如 jacoco 插件解析失败）；编译与镜像构建幂等，重试 3 次吸收。
    retry_n 3 mvn -q -DskipTests compile

    echo "== compose build ==" | tee -a "$REPORT"
    retry_n 3 $COMPOSE build

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
