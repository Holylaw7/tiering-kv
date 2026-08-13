# ADR-0285: Cluster Pub/Sub Forwarding

## Status

Accepted

## Context

PubSubForwarder 只有 SPI，无网络实现；跨节点广播缺失。

## Decision

采用 Netty RPC 转发：

- `RpcMessageType.PUBSUB(32)` / `PUBSUB_RESPONSE(33)`（additive）；
- `RpcPubSubForwarder`：peer 注册 + RpcClient 发送 + 失败登记；
- 环回抑制：不转发回来源节点；
- 接收端 RpcServer handler 解码后调用本地 broker.publish；
- best-effort：发送失败只登记不阻塞。

## Alternatives

1. 无转发：单节点 Pub/Sub；
2. 同步等待全部分发：阻塞发布路径；
3. 复用非 RPC 通道：与既有安全 RPC 能力脱节。

## Consequences

优点：复用 RPC 传输/安全、环回抑制、失败可审计。

缺点：至少一次语义需消费方幂等。

风险：RPC 消息类型扩展需保持既有类型 wire 值不变。

## Implementation

`RpcMessageType` 扩展、`io.tieringkv.pubsub.RpcPubSubForwarder` +
`src/test/java/io/tieringkv/pubsub/RpcPubSubForwarderTest.java`。
