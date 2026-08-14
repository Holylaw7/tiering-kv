#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 冷/热性能基线（ADR-0322，TD-009）：
# 进程内口径：冷 = 空 BlockCache 首次 mmap 全量读取；热 = BlockCache
# 预热后二次读取。root 时额外 drop caches 覆盖 OS 页缓存口径。
# 用法：scripts/cold-cache-bench.sh [cold|hot|both]
MODE="${1:-both}"

run_bench() {
  mvn -B -Dsurefire.excludedGroups= \
    -Dtest=ColdCacheBenchmarkTest -DfailIfNoTests=false test 2>&1 \
    | grep -E "PHASE61-BENCH"
}

case "$MODE" in
  cold)
    if [ "$(id -u)" = "0" ]; then
      sync
      echo 3 > /proc/sys/vm/drop_caches || true
    fi
    echo "[cold-cache]"
    run_bench
    ;;
  hot)
    echo "[hot-cache]"
    run_bench
    ;;
  both)
    if [ "$(id -u)" = "0" ]; then
      sync
      echo 3 > /proc/sys/vm/drop_caches || true
    fi
    echo "[cold-cache]"
    run_bench
    echo "[hot-cache]"
    run_bench
    ;;
  *)
    echo "unknown mode: ${MODE} (cold|hot|both)" >&2
    exit 2
    ;;
esac
