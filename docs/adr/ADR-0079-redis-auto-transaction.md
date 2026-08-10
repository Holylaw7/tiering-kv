# ADR-0079: Redis Auto Transaction Model

## Status

Accepted

## Context

Phase 19 建立了完整 MVCC/Percolator 事务内核，但 Redis 网关仍直连
StorageEngine（TD-042）。用户执行 SET 只是单条底层写入，不经过
prewrite/commit，无法获得事务一致性；GET 也不是快照读。

## Decision

网关层新增自动事务包装，Redis 用户无感：

- `AutoTransactionExecutor`：GET = `readTS = HLC.now()` 快照读；
  SET/DEL = 单键事务（BEGIN → prewrite → commit → END）；
  MGET = 多键快照读；MSET = 单事务多键（跨 shard 走
  TransactionCoordinator）；
- `TransactionCommandHandler`：网关命令入口委托给
  AutoTransactionExecutor，并记录 `redis_txn_latency`；
- RESP 协议不变，用户无需显式 BEGIN；
- 网关保持“非本地键返回 MOVED”语义；
- 未配置 MVCC 层时回退到原直连路径（Phase 18 行为不回归）。

## Alternatives

1. 网关强制要求用户 BEGIN/MULTI：破坏 Redis 兼容性。
2. 直接在 StorageEngine 内嵌事务：耦合复制与存储，违反 SPI 分层。
3. 仅 GET 快照读、SET 直写：写路径无事务语义。

## Consequences

优点：

- 单键强一致，多键具备快照隔离；
- 网关 API 与 RESP 兼容，Phase 18 测试不回归。

缺点：

- 每条 SET 由一次底层写变为 2PC（多次 Raft 提案），吞吐下降；
- 与普通 Redis 相比延迟增加（事务提交路径）。

风险：

- 低；性能目标 SET >100K ops/s 需基准验证，不达标如实登记 TD。

## Implementation

- `src/main/java/io/tieringkv/cluster/gateway/`：
  AutoTransactionExecutor、TransactionCommandHandler；
- `RedisClusterGateway` 增加可选 MVCC 参与者注入；
- 测试：RedisMvccIntegrationTest（SET→GET、SET→DEL、并发写冲突、
  leader failover）；基准：GET >500K、SET >100K。
