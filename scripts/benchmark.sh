#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# v4 M4（ADR-0322）：真实性能基线入口。
# 运行核心 benchmark 测试（含 phase58/59/60 新增），
# 收集 PHASExx-BENCH 输出到 target/benchmark-results.txt。
set -euo pipefail

SUITE=${1:-core}
OUT="${2:-target/benchmark-results.txt}"
mkdir -p target

case "$SUITE" in
  core)
    TESTS='HnswSearchBenchmarkTest,VectorStorageBenchmarkTest,MultiModelEncodingBenchmarkTest,CrossClusterReplicationBenchmarkTest,ConcurrencyBenchmarkTest'
    ;;
  full)
    TESTS='Phase24BenchmarkTest,Phase25BenchmarkTest,Phase26BenchmarkTest,Phase27BenchmarkTest,Phase28BenchmarkTest,Phase29BenchmarkTest,Phase30BenchmarkTest,Phase31BenchmarkTest,Phase32BenchmarkTest,Phase33BenchmarkTest,Phase34BenchmarkTest,Phase35BenchmarkTest,Phase36BenchmarkTest,Phase37BenchmarkTest,Phase38BenchmarkTest,Phase39BenchmarkTest,Phase40BenchmarkTest,Phase41BenchmarkTest,Phase42BenchmarkTest,Phase43BenchmarkTest,Phase44BenchmarkTest,Phase45BenchmarkTest,Phase46BenchmarkTest,Phase47BenchmarkTest,Phase48BenchmarkTest,Phase49BenchmarkTest,Phase50BenchmarkTest,Phase51BenchmarkTest,Phase52BenchmarkTest,Phase53BenchmarkTest,Phase54BenchmarkTest,Phase55BenchmarkTest,Phase56BenchmarkTest,HnswSearchBenchmarkTest,VectorStorageBenchmarkTest,MultiModelEncodingBenchmarkTest,CrossClusterReplicationBenchmarkTest,ConcurrencyBenchmarkTest'
    ;;
  *)
    echo "unknown suite: $SUITE (core|full)" >&2
    exit 2
    ;;
esac

echo "running benchmark suite: $SUITE"
mvn -B -Dsurefire.excludedGroups= -Dtest="$TESTS" \
  -DfailIfNoTests=false test 2>&1 \
  | tee target/benchmark-run.log \
  | grep -E "PHASE[0-9]+-BENCH" > "$OUT" || true

if [ ! -s "$OUT" ]; then
  echo "no benchmark results captured" >&2
  exit 3
fi
echo "benchmark results: $OUT"
cat "$OUT"
