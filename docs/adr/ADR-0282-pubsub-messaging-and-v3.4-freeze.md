# ADR-0282: Pub/Sub Messaging & v3.4 Freeze

## Status

Accepted

## Context

缺少实时消息能力；集群广播需要 SPI 预留。

## Decision

本地 Pub/Sub + 集群广播接口：

- `PubSubBroker`：channel/pattern 订阅 + 发布（本地至少一次）；
- `PubSubForwarder` SPI：跨节点转发预留（网络实现 Phase 53+）；
- 命令：SUBSCRIBE/UNSUBSCRIBE/PSUBSCRIBE/PUNSUBSCRIBE/PUBLISH；
- 连接级投递接线 Phase 53（本阶段 broker + 命令确认语义）；
- 同时冻结 v3.4：pom 3.4.0-SNAPSHOT、release.yml v3.4.0、
  Phase52 基准、全量回归 ≥13700 次测试执行（Surefire 口径）。

## Alternatives

1. 直接跨节点网络实现：依赖未就绪的传输层；
2. 无 broker 只做命令壳：语义不可测；
3. 不冻结持续加命令：版本不可发布。

## Consequences

优点：本地语义完整、SPI 可演进、版本可发布。

缺点：连接级投递与跨节点广播待 Phase 53+。

风险：至少一次投递需幂等消费方配合。

## Implementation

`io.tieringkv.pubsub.{PubSubBroker,PubSubForwarder,Subscriber}`、
`io.tieringkv.command.PubSubCommand`、
`src/test/java/io/tieringkv/pubsub/PubSubBrokerTest.java`、
`src/test/java/io/tieringkv/ci/ReleaseV34Test.java`、
`docs/release/v3.4.0-release-notes.md`。
