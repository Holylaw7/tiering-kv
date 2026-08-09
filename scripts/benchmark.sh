#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# TODO(phase9): 接入 JMH / 连接压测套件后启用真实执行
echo "benchmark suite not available until Phase 9" >&2
exit 1
