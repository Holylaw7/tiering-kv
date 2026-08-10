# ADR-0068: TCP Gateway Architecture

## Status

Accepted

## Context

Phase 17 网关为 handler 级，无真实 TCP 服务。生产网关需要真实 socket
协议栈：EventLoop → RESP Decoder → CommandDispatcher → UnifiedRouter。

## Decision

- `NettyClusterGateway`：独立 Netty TCP 服务（复用 RESP2
  Decoder/Encoder），事件循环内解析与分派；
- `ClusterCommandHandler`：命令 → UnifiedRouter 路由 → 本地/远端执行；
  - 本地键：存储引擎执行；
  - 远端键：`MOVED slot host:port`；
  - 迁移中：`ASK`；临时不可用：`TRYAGAIN`；
  - 脚本命令：`NOSCRIPT`（占位，本阶段无 Lua）；
- 命令：GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS/CLUSTER NODES；
- 连接模型：每连接独立解析状态；pipeline 支持；多连接并行；
- 认证与限流沿用 RPC 安全层思路（本阶段记录为限制）。

## Alternatives

1. 复用 TieringKvServer + CommandEngine：无法表达集群路由，否决。
2. HTTP 网关：协议不兼容 Redis，否决。
3. 每请求线程模型：并发受限，否决（沿用 Netty EventLoop）。

## Consequences

优点：真实 Redis 协议兼容；pipeline/并发真实可测；路由与执行解耦。

缺点：网关单点（多实例需负载均衡，后续阶段）。

风险：RESP 编解码错误需严格测试（协议集成测试覆盖）。

## Implementation

- `cluster/gateway/NettyClusterGateway.java`、`ClusterCommandHandler.java`
- 测试：GatewayIntegrationTest（≥30，真实 socket）。
