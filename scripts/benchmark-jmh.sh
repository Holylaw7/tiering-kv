#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# JMH 基准（ADR-0267）：核心路径可复现基准。
# 用法：./scripts/benchmark-jmh.sh [includes-pattern]
INCLUDES="${1:-MemTableGetBenchmark|WalAppendBenchmark|SstableRandomReadBenchmark}"
echo "jmh includes: ${INCLUDES}"
mvn -q test-compile
mvn -q jmh:benchmark -Dincludes="${INCLUDES}"
echo "jmh: results under target/jmh-results/"
