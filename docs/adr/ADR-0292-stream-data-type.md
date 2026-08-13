# ADR-0292: Stream Data Type

## Status

Accepted

## Context

缺少流式数据结构与消费基础。

## Decision

采用 STREAM 类型（TypedValueCodec 标签 5）+ StreamCodec：

- XADD：自增 id（毫秒-序号）/ 显式 id；字段表；
- XREAD / XLEN / XRANGE / XTRIM MAXLEN；
- 段锁原子更新；空 stream 保留（Redis 语义）；
- WAL 以 value 字节整体落盘（冻结格式不变）。

## Alternatives

1. List 模拟：无 id 语义；
2. 改 WAL 格式：冻结破坏；
3. 无持久化：重启丢数据。

## Consequences

优点：id 语义完整、原子、持久化兼容。

缺点：整值重写 O(n)。

风险：id 并发唯一性依赖段锁。

## Implementation

`ValueType.STREAM`、`StreamCodec`、`io.tieringkv.command.StreamCommand` +
`src/test/java/io/tieringkv/command/StreamCommandFamilyTest.java`。
