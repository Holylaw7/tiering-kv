#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 覆盖率门禁（ADR-0264）：解析 JaCoCo CSV，line 覆盖率低于阈值退出非零。
THRESHOLD="${COVERAGE_THRESHOLD:-70}"
CSV="target/site/jacoco/jacoco.csv"
if [[ ! -f "$CSV" ]]; then
  echo "coverage-check: ${CSV} missing; run mvn test first" >&2
  exit 1
fi

awk -F',' 'NR > 1 { lm += $4; lc += $5 } END {
  if (lm + lc == 0) { print "coverage-check: no instructions"; exit 1 }
  pct = lc * 100.0 / (lm + lc)
  printf "coverage-check: line coverage %.2f%% (threshold %d%%)\n", pct, ENVIRON["COVERAGE_THRESHOLD"]
  if (pct < ENVIRON["COVERAGE_THRESHOLD"]) exit 1
}' "$CSV" || exit 1
echo "coverage-check: OK"
