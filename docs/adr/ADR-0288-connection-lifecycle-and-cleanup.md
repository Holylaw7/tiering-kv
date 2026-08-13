# ADR-0288: Connection Lifecycle & Cleanup

## Status

Accepted

## Context

连接级状态（协议版本/订阅/事务队列）在断线或异常后必须清理。

## Decision

采用生命周期清理：

- channelInactive → 退订全部 channel/pattern、清空事务队列、
  重置协议版本；
- broker.unsubscribeAll(subscriber) 保证计数一致；
- 订阅计数与 broker 状态矩阵验证。

## Alternatives

1. 依赖 GC：订阅注册表泄漏；
2. 只清队列不清订阅：broker 状态漂移。

## Consequences

优点：无泄漏、状态一致、可审计。

缺点：需要在所有连接关闭路径调用。

风险：异步关闭竞态需事件循环内执行。

## Implementation

CommandHandler.channelInactive、ConnectionContext.cleanup +
`src/test/java/io/tieringkv/session/SessionLifecycleTest.java`。
