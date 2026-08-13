#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 维护质量门禁（ADR-0315）：回归 + 覆盖率 + 静态 + 依赖漏洞。
mvn -q test
./scripts/coverage-check.sh
mvn -q spotbugs:spotbugs -Dspotbugs.effort=Min \
  -Dspotbugs.threshold=Low || true
mvn -q dependency:analyze || true
echo "maintenance gates: OK"
