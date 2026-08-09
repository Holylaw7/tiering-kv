#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# TODO(phase9): 接入 tests/stress 与 benchmarks 后启用
echo "stress suite not available until Phase 9" >&2
exit 1
