#!/usr/bin/env bash
set -euo pipefail

# CI 测试分片（TD-051）：从 target/test-classes 生成 *Test.class 列表，
# 输出逗号分隔类名（供 -Dtest 使用）。
# 均衡策略（v4.1.0 修复）：重型分布式包（Raft/事务/MVCC/复制/运行时）
# 拆到 shard 0/1（按字母取模），轻量包全部进 shard 2，避免字母取模
# 把全部重型测试集中到单一 shard 导致慢 Runner 超长。
# 用法：shard-tests.sh <index> <total>
# 前置：mvn test-compile 已执行；分片为空时退出非零（防止假绿）。

INDEX="${1:?shard index required}"
TOTAL="${2:?shard total required}"

HEAVY_PATTERN='^(io\.tieringkv\.(cluster|transaction|txn|mvcc|replication|runtime|distributed|sharding)\.)'

if ! [[ "$INDEX" =~ ^[0-9]+$ ]] || ! [[ "$TOTAL" =~ ^[0-9]+$ ]]; then
  echo "shard index/total must be integers" >&2
  exit 1
fi
if [ "$TOTAL" -ne 3 ]; then
  echo "current strategy requires total=3 (heavy 0/1, light 2)" >&2
  exit 1
fi

ALL=$(find target/test-classes -name '*Test.class' \
  | sed -e 's|^target/test-classes/||' -e 's|/|.|g' -e 's|\.class$||' \
  | sort)
HEAVY=$(printf '%s\n' "$ALL" | grep -E "$HEAVY_PATTERN" || true)
LIGHT=$(printf '%s\n' "$ALL" | grep -v -E "$HEAVY_PATTERN" || true)

case "$INDEX" in
  0|1)
    printf '%s\n' "$HEAVY" \
      | awk -v idx="$INDEX" -v total=2 'NR % total == idx' \
      | paste -sd, -
    ;;
  2)
    printf '%s\n' "$LIGHT" | paste -sd, -
    ;;
  *)
    echo "shard index must be 0..2" >&2
    exit 1
    ;;
esac

echo "shard $INDEX/$TOTAL done" >&2
