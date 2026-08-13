# ADR-0284: Pub/Sub Connection Delivery

## Status

Accepted

## Context

Pub/Sub broker 存在但订阅者无法绑定连接，消息无法投递到客户端。

## Decision

采用连接级订阅者 + 有界队列：

- `ConnectionSubscriber`：有界队列（默认 1024）+ 丢弃计数；
- SUBSCRIBE/PSUBSCRIBE 注册当前连接订阅者（ConnectionContext）；
- 消息投递格式：RESP3 Push / RESP2 数组；
- 事件循环在响应批次后 drain 队列并写出；
- broker 增加 unsubscribeAll 供断线清理。

## Alternatives

1. 全局默认队列：无法区分连接；
2. 无界队列：OOM 风险；
3. 同步直接写 channel：事件循环外写不安全。

## Consequences

优点：连接隔离、背压可观测、清理可闭环。

缺点：队列溢出丢消息（至少一次语义靠重连补偿）。

风险：drain 时机需与响应保序协调。

## Implementation

`io.tieringkv.pubsub.ConnectionSubscriber`、PubSubCommand、
CommandHandler +
`src/test/java/io/tieringkv/pubsub/ConnectionPubSubTest.java`。
