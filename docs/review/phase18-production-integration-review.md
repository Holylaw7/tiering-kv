# Phase 18 评审报告：分布式生产集成

Phase 18 · 2026-08-10

## 1. Architecture

```text
Client → ClusterGateway(TCP) → RequestRouter → UnifiedRoutingLayer
        （Region Route + Slot Route 统一）
        → RegionManager → RegionLifecycleService(Split/Merge/Transfer/Move)
        → Multi-Raft Group → StorageEngine
```

Phase 18 完成统一路由、真实 TCP 网关、Split/Merge 与 Raft 联动、
生产化迁移、三节点部署产物与完整可观测性。

## 2. ADR

| ADR | 决策 |
| --- | --- |
| 0066 Unified Routing | RoutingTable（键范围+slot 区间+epoch）+ RoutingCache 自刷新 + RouteEpochGuard |
| 0067 Region Raft Migration | split/merge 绑定独立 Raft 组 + 快照装载 + 路由切换 + 回滚 |
| 0068 TCP Gateway | Netty EventLoop → RESP → CommandDispatcher → UnifiedRouter |
| 0069 Cross Machine | 三节点 compose + tc netem 脚本 + transport 级等价混沌 |
| 0070 Production Metrics | INFO CLUSTER 聚合 + Prometheus MetricsExporter |

## 3. Implementation

- UnifiedRoutingLayer：RoutingTable/UnifiedRouter/RoutingCache/
  RouteEpochGuard（23 测试）；
- NettyClusterGateway：真实 TCP + pipeline 批量 flush（GET 719K/
  SET 590K ops/s）+ MOVED/ASK/TRYAGAIN + CLUSTER SLOTS/NODES；
- RegionRaftMigrationManager：split/merge 元数据+数据+子组+路由原子
  切换，失败回滚（仅生命周期状态复位）、恢复幂等；
- Migration 生产化：ByteRateLimiter + MigrationScheduler（IO/backlog
  自适应）+ 指标；
- Metrics：MetricsExporter（Prometheus）+ ProductionInfo（INFO CLUSTER
  聚合 Region/Raft/Migration/Gateway）；
- 部署：docker-compose.cluster.yml（3 节点独立 JVM/卷/网络）。

## 4. Tests

新增 165 项（Phase 17 基线 947）：

| 模块 | 新增 | 结果 |
| --- | --- | --- |
| UnifiedRoutingTest | 23 | ✅ |
| GatewayIntegrationTest（真实 socket） | 31 | ✅ |
| SplitRaftIntegrationTest | 33 | ✅ |
| MergeRaftIntegrationTest | 25 | ✅ |
| MigrationProductionTest | 20 | ✅ |
| CrossMachineChaosTest | 20 | ✅ |
| Phase18MetricsTest | 11 | ✅ |
| GatewayBenchmarkTest | 2 | ✅ |

全量回归 **1112/1112 全绿**（目标 >1100 ✅）。

## 5. Benchmark

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| Gateway GET / SET | 719K / 590K ops/s | >500K / >200K | ✅ |
| 迁移 100B / 1KB | 209.1 / 986.0 MB/s | >100 / >300 | ✅ |
| Split / Merge 1M | ~0.9 / ~0.7 s | <10 / <20 s | ✅ |

## 6. Chaos

- CrossMachineChaosTest 20 项：leader 击杀/分区恢复/重启追赶/快照
  追赶/迁移中断/双组隔离/延迟丢包变体；
- 迁移中断：chunk 检查点续传；暂停/恢复；击杀与迁移并发。

## 7. Limitations（不隐藏）

1. 三节点 compose 未在本机执行（无 Docker 守护进程），混沌为
   transport 级等价验证；
2. CLUSTER NODES 为简化格式（非 Redis 全字段）；
3. 路由表为 synchronized 实现（读高频场景可演进 copy-on-write）；
4. Split/Merge 子组数据装载为内存级快照，跨机需经 RPC 快照传输；
5. 网关无认证/限流/TLS（沿用安全层为后续工作）。

## 8. Next Phase

- 跨机容器混沌执行（Linux+Docker + tc netem）；
- 快照 RPC 传输 + 子组跨机数据搬迁；
- 网关认证/限流/TLS；CLUSTER NODES 全字段；路由表 copy-on-write。

**定位**：Tiering-KV 已具备统一路由 + 真实 TCP Redis Cluster 网关 +
Raft 联动生命周期 + 生产化迁移 + 完整可观测性。
