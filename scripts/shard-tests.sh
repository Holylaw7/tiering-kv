#!/usr/bin/env bash
set -euo pipefail

# CI 测试分片（TD-051）：从 target/test-classes 生成 *Test.class 列表，
# 按 shard index 均分并输出逗号分隔类名（供 -Dtest 使用）。
# 用法：shard-tests.sh <index> <total>
# 前置：mvn test-compile 已执行；分片为空时退出非零（防止假绿）。

INDEX="${1:?shard index required}"
TOTAL="${2:?shard total required}"

if ! [[ "$INDEX" =~ ^[0-9]+$ ]] || ! [[ "$TOTAL" =~ ^[0-9]+$ ]]; then
  echo "shard index/total must be integers" >&2
  exit 1
fi
if [ "$INDEX" -ge "$TOTAL" ]; then
  echo "shard index must be < total" >&2
  exit 1
fi

find target/test-classes -name '*Test.class' \
  | sed -e 's|^target/test-classes/||' -e 's|/|.|g' -e 's|\.class$||' \
  | sort \
  | awk -v idx="$INDEX" -v total="$TOTAL" 'NR % total == idx' \
  | paste -sd, -

echo "shard $INDEX/$TOTAL done" >&2
