#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 质量门禁三件套（ADR-0264）：覆盖率 + 静态分析 + 依赖审计。
echo "== jacoco report =="
mvn -q jacoco:report
echo "== coverage check =="
./scripts/coverage-check.sh
echo "== spotbugs =="
mvn -q spotbugs:spotbugs -Dspotbugs.effort=Min \
  -Dspotbugs.threshold=Low || true
echo "== dependency analyze =="
mvn -q dependency:analyze || true
echo "quality-gates: done (reports under target/)"
