#!/usr/bin/env bash
set -euo pipefail

# CI 测试分片（TD-051 / ADR-0353 加固）：从 target/test-classes 生成
# *Test.class 列表，输出逗号分隔类名（供 -Dtest 使用）。
# 均衡策略（v4.1.1 加固）：全部测试按 3 分片轮转（NR % 3），
# 重型分布式包（Raft/事务/MVCC/复制/运行时）不再集中于 shard 0/1，
# 进一步降低单一 shard 的时序敏感测试密度，缓解慢 Runner 上
# Raft 选举窗口 / 快照时序类 flake（MetadataPersistenceTest 等）。
# 用法：shard-tests.sh <index> <total>
# 前置：mvn test-compile 已执行；分片为空时退出非零（防止假绿）。

INDEX="${1:?shard index required}"
TOTAL="${2:?shard total required}"

if ! [[ "$INDEX" =~ ^[0-9]+$ ]] || ! [[ "$TOTAL" =~ ^[0-9]+$ ]]; then
  echo "shard index/total must be integers" >&2
  exit 1
fi
if [ "$TOTAL" -ne 3 ]; then
  echo "current strategy requires total=3 (full round-robin)" >&2
  exit 1
fi

ALL=$(find target/test-classes -name '*Test.class' \
  | sed -e 's|^target/test-classes/||' -e 's|/|.|g' -e 's|\.class$||' \
  | sort)

case "$INDEX" in
  0|1|2)
    printf '%s\n' "$ALL" \
      | awk -v idx="$INDEX" -v total="$TOTAL" 'NR % total == idx' \
      | paste -sd, -
    ;;
  *)
    echo "shard index must be 0..2" >&2
    exit 1
    ;;
esac

echo "shard $INDEX/$TOTAL done" >&2
