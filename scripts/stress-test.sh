#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# 压力/混沌执行入口（收尾说明）：真实压力与混沌已由 CI 门禁覆盖——
# transaction-e2e（container-chaos + netem + block-device）、
# 压力测试类（tests/stress）与 benchmark 组（release Benchmark 步骤）。
# 如需本机一键压力演练，请直接运行对应测试类：
#   mvn -Dtest='ConcurrencyBenchmarkTest,ChaosValidationTest' test
echo "stress suite: 由 CI 门禁覆盖（transaction-e2e / benchmark / chaos）" >&2
exit 1
