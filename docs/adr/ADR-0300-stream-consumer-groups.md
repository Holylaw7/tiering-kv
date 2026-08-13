# ADR-0300: Stream Consumer Groups

## Status

Accepted

## Context

Stream 无消费组，无法支撑多消费者语义。

## Decision

采用消费组基础能力：

- XGROUP CREATE/DESTROY；XREADGROUP GROUP g consumer；
- XACK / XPENDING；组状态（last-delivered + pending）；
- StreamCodec additive 扩展：条目后追加组段（旧数据兼容解码）；
- 组状态随 value 持久化（重启恢复）。

## Alternatives

1. 独立组状态文件：与数据分离，恢复复杂；
2. 改 WAL 格式：冻结破坏；
3. 仅内存：重启丢消费位置。

## Consequences

优点：持久化、additive、旧数据兼容。

缺点：整值重写 O(n)。

风险：组段解析需兼容旧格式。

## Implementation

StreamCodec 扩展 + StreamCommand 消费组命令 +
`src/test/java/io/tieringkv/command/StreamConsumerGroupTest.java`、
`docs/design/stream-consumer-groups.md`。
