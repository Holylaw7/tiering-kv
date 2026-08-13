# ADR-0307: Consumer Group Advanced Capabilities

## Status

Accepted

## Context

消费组无重新声明/死信能力。

## Decision

采用 XCLAIM/XAUTOCLAIM：

- 显式/自动重新声明 pending 到指定消费者；
- 重复投递计数（Group.deadLetters）additive 编码；
- 组段编码向后兼容（旧数据默认 deadLetters=0）。

## Consequences

优点：PEL 可管理、死信可审计。

缺点：min-idle 语义为简化实现。

风险：编码兼容需矩阵覆盖。

## Implementation

StreamCodec/StreamCommand 扩展 +
`src/test/java/io/tieringkv/command/ConsumerGroupAdvancedTest.java`、
`docs/design/consumer-group-advanced.md`。
