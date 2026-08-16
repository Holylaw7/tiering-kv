#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 可复现基准入口（docs/benchmark/reproducible-benchmark-guide.md）：
# 固定 workload / 轮次 / 环境快照，覆盖四组简历核心数字。
# 用法：
#   scripts/reproducible-benchmark.sh              # 内存 + IO + 网络（3 轮）
#   scripts/reproducible-benchmark.sh --rounds 5   # 正式 5 轮
#   scripts/reproducible-benchmark.sh --server     # 追加 Level B（较重）
#   scripts/reproducible-benchmark.sh --cold       # 追加 cold-cache（Linux root）
#   scripts/reproducible-benchmark.sh --quick      # 1 轮冒烟

ROUNDS=3
RUN_SERVER=0
RUN_COLD=0

while (($#)); do
  case "$1" in
    --rounds)
      ROUNDS="${2:?--rounds requires a number}"
      shift 2
      ;;
    --server)
      RUN_SERVER=1
      shift
      ;;
    --cold)
      RUN_COLD=1
      shift
      ;;
    --quick)
      ROUNDS=1
      RUN_SERVER=0
      RUN_COLD=0
      shift
      ;;
    *)
      echo "unknown option: $1" >&2
      exit 2
      ;;
  esac
done

STAMP=$(date +%Y%m%d-%H%M%S)
OUT="target/reproducible-benchmark/$STAMP"
mkdir -p "$OUT"

{
  echo "date=$(date -Is 2>/dev/null || date)"
  echo "rounds=$ROUNDS"
  java -version 2>&1 || true
  uname -a 2>/dev/null || true
  echo "nproc=$(nproc 2>/dev/null || echo unknown)"
  free -h 2>/dev/null || true
  df -h . 2>/dev/null || true
} > "$OUT/env.txt"

run_suite() {
  local name="$1" tests="$2" grep_pattern="$3"
  for r in $(seq 1 "$ROUNDS"); do
    echo "=== round $r/$ROUNDS: $name ==="
    mvn -B -Dsurefire.excludedGroups= -Dtest="$tests" \
      -DfailIfNoTests=false test 2>&1 \
      | grep -E "$grep_pattern" >> "$OUT/$name.txt" || true
  done
}

run_suite memory "MemoryEngineBenchmarkTest" "MEM-BENCH"
run_suite io "IOBenchmarkTest" "IO-BENCH"
run_suite network "NetworkEndToEndLatencyBenchmarkTest" "NETWORK-BENCH"

if [ "$RUN_SERVER" = 1 ]; then
  run_suite server "ProductionBenchmarkTest#levelBServerBenchmark" "LEVEL-B"
fi

if [ "$RUN_COLD" = 1 ]; then
  if [ "$(id -u)" != "0" ]; then
    echo "cold-cache requires root; skipping (run with sudo)" >&2
  else
    sync
    echo 3 > /proc/sys/vm/drop_caches || true
    run_suite cold "ColdCacheBenchmarkTest" "PHASE61-BENCH"
  fi
fi

{
  echo "# Reproducible Benchmark $STAMP"
  echo
  echo "- rounds=$ROUNDS server=$RUN_SERVER cold=$RUN_COLD"
  echo "- env: env.txt / memory: memory.txt / io: io.txt /"
  echo "  network: network.txt"
  [ -f "$OUT/server.txt" ] && echo "  server: server.txt"
  [ -f "$OUT/cold.txt" ] && echo "  cold: cold.txt"
} > "$OUT/SUMMARY.md"

echo "results: $OUT"
for f in "$OUT"/*.txt; do
  echo "--- $f"
  cat "$f"
done
