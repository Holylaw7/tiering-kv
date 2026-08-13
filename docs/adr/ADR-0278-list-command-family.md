# ADR-0278: List Command Family

## Status

Accepted

## Context

List 支持消息队列/时间线，需要头尾操作、索引与裁剪。

## Decision

采用顺序数组 + 头尾操作：

- LPUSH/RPUSH/LPOP/RPOP/LLEN/LRANGE/LINDEX/LSET/LREM/LTRIM；
- 负数索引与 Redis 一致；空 list 自动删键；
- 写路径段锁原子（AtomicStringOps.update）；
- 非 list WRONGTYPE。

## Alternatives

1. 双向链表持久化：序列化复杂度高；
2. 每元素拆键：TTL/原子性破坏；
3. 只支持头尾：命令族不完整。

## Consequences

优点：语义完整、原子、删键行为正确。

缺点：头尾 O(1) 索引但整值重写 O(n)。

风险：LREM/LTRIM 边界需矩阵覆盖。

## Implementation

`io.tieringkv.command.ListCommand` +
`src/test/java/io/tieringkv/command/ListCommandFamilyTest.java`。
