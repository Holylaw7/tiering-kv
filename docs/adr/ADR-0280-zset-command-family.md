# ADR-0280: ZSet Command Family

## Status

Accepted

## Context

有序集合支撑排行榜/范围查询。

## Decision

采用 (score, member) 排序语义：

- ZADD/ZSCORE/ZRANGE/ZREVRANGE/ZREM/ZCARD/ZINCRBY/ZRANGEBYSCORE/
  ZCOUNT/ZRANK/ZREVRANK；
- score double，NaN 拒绝；同分按成员字典序；
- ZINCRBY 段锁原子；空 zset 自动删键；非 zset WRONGTYPE。

## Alternatives

1. 跳表索引：实现复杂度高（可后续演进）；
2. 每次全排序：正确但 O(n log n)（当前取舍）；
3. 只存 score 不排序：ZRANGE 语义缺失。

## Consequences

优点：语义完整、原子、可排序。

缺点：每次 ZRANGE 全排序，性能受限（文档登记）。

风险：double 精度与 -inf/+inf 需矩阵覆盖。

## Implementation

`io.tieringkv.command.ZSetCommand` +
`src/test/java/io/tieringkv/command/ZSetCommandFamilyTest.java`。
