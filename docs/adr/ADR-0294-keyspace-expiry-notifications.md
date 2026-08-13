# ADR-0294: Keyspace Expiry Notifications

## Status

Accepted

## Context

键过期无事件，客户端无法感知 TTL 清理。

## Decision

采用 keyspace 过期通知：

- MemTable 过期路径（惰性/主动）移除成功后发布
  `__keyspace@0__:<key>` expired 到 PubSubBroker；
- 通知开关（默认开，可配置关闭）；
- 本地至少一次，不落盘。

## Alternatives

1. 不通知：客户端靠轮询；
2. 改 WAL：格式破坏；
3. 全局广播：噪声大。

## Consequences

优点：感知闭环、开关可控。

缺点：通知不保证送达顺序。

风险：高频过期产生通知压力。

## Implementation

MemTable 过期路径 + `KeyspaceNotifications` 开关 +
`src/test/java/io/tieringkv/storage/memory/KeyspaceExpiryNotificationTest.java`。
