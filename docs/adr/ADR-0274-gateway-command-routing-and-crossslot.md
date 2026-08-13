# ADR-0274: Gateway Command Routing & CROSSSLOT

## Status

Accepted

## Context

Redis Cluster 网关只支持 GET/SET/DEL/MGET/MSET，且未做多键跨槽校验；
新命令族需要网关路由，集群语义需要 CROSSSLOT。

## Decision

网关扩展：

- 单键命令（INCR/APPEND/TTL/EXPIRE/TYPE 等）按 slot 路由，非本地
  返回 MOVED；
- 多键命令（MGET/MSET/MSETNX/DEL/EXISTS）先校验全部键同槽，
  跨槽返回 `CROSSSLOT Keys in request don't hash to the same slot`；
- 节点本地命令（SCAN/DBSIZE/FLUSHDB/CONFIG/CLIENT/COMMAND）本地
  执行（真实 Redis Cluster 亦为节点本地语义）；
- 命令执行复用 CommandEngine + 本地存储，避免网关重复实现语义。

## Alternatives

1. 网关复制命令逻辑：双重维护；
2. 多键不做槽校验：集群数据错乱；
3. 所有命令强制 MOVED：可用性差。

## Consequences

优点：路由一致、命令复用、集群语义正确。

缺点：多键命令要求同槽，客户端需按 hash tag 组织键。

风险：事务网关路径与新命令族并存需保持行为一致。

## Implementation

`io.tieringkv.cluster.gateway.RedisClusterGateway` +
`src/test/java/io/tieringkv/gateway/GatewayCommandRoutingTest.java`。
