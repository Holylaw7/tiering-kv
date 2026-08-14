#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 年度复核（ADR-0314）：文档/基准/能力矩阵/门禁检查。
OUT="${1:-target/annual-review.txt}"
{
  echo "== annual review =="
  echo "docs: $(find docs -name '*.md' | wc -l) files"
  echo "benchmark: $(find docs/benchmark -name '*.md' | wc -l) reports"
  echo "gates: $(find docs/adr -name 'ADR-*.md' | wc -l) adrs"
  echo "tests: $(find src/test -name '*.java' | wc -l) files"
} > "$OUT"
cat "$OUT"
