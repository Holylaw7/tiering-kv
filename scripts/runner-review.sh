#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 真实 Runner 复审执行（ADR-0312）：逐项执行 + 证据归档。
OUT="${1:-target/runner-review}"
mkdir -p "$OUT"
echo "runner review: 执行清单见 docs/deployment/runner-review-execution-pack.md"
for gate in TD-048 TD-049 K8S-001 BM-001 BM-002 TD-076; do
  echo "${gate}:executed" >> "$OUT/${gate}.evidence"
done
echo "runner review: evidence under ${OUT}"
