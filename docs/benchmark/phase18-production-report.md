# Phase 18 生产集成基准报告

Phase 18 · 2026-08-10

## 1. 方法说明（如实声明）

- 网关：真实 TCP + pipeline（4096/批，缓冲客户端），RESP2；
- 迁移：进程内按段并行（8 worker）；
- Split：200K 实测，1M/10M 为线性外推；
- 混沌恢复：进程内 leader 击杀；快照恢复为重启追赶（<10s 断言界）。

## 2. Redis Cluster Gateway（真实 TCP）

| 命令 | ops/s | P50 | P95 | P99 | 目标 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | 719 K | <0.01ms | <0.01ms | 0.001ms | >500K | ✅ |
| SET | 590 K | <0.01ms | <0.01ms | 0.005ms | >200K | ✅ |

实现：事件循环内批量编码 + `channelReadComplete` 单次 flush。

## 3. 生产化迁移

| 条目大小 | 吞吐 | 目标 | 状态 |
| --- | --- | --- | --- |
| 100B | 209.1 MB/s | >100 MB/s | ✅ |
| 1KB | 986.0 MB/s | >300 MB/s | ✅ |
| 10KB | 1952.1 MB/s | — | ✅ |

新增：ByteRateLimiter（字节/秒）、MigrationScheduler（IO 压力降并发/
backlog 增并发）、migration_remaining/migration_error 指标。

## 4. Split / Merge（Raft 联动）

| 操作 | 200K 实测 | 1M 外推 | 10M 外推 | 目标 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Split | 0.186s | ~0.9s | ~9.3s | <10s | ✅ |
| Merge | 0.142s | ~0.7s | ~7.1s | <20s | ✅ |

子/合并 Raft 组创建、路由原子切换、失败回滚、重启恢复（集成测试覆盖）。

## 5. 混沌恢复

| 场景 | 结果 |
| --- | --- |
| leader 击杀 → 新 leader | p50 183ms（<5s ✅） |
| 快照恢复（重启追赶） | 空日志追赶 ≤10s（断言界） |
| 迁移中断恢复 | chunk 检查点续传，数据完整 |

## 6. 复现

- `mvn -Dtest=GatewayBenchmarkTest test`
- `mvn -Dtest=ParallelMigrationBenchmarkTest test`
- `mvn -Dtest=Phase17RegionBenchmarkTest test`
- 输出前缀：`PHASE18-BENCH` / `PHASE17-BENCH`
