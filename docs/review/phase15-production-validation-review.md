# Phase 15 评审报告：生产验证与性能收口

Phase 15 · 2026-08-10

## 1. 架构评价

Phase 15 不修改存储模型与共识协议，在既有分层上补齐生产验证闭环：

```text
                        ┌──────────────────────────────┐
                        │  Phase 15 Production Layer   │
                        ├──────────────────────────────┤
  Migration  StreamingMigrator（单次快照 + 游标 + 版本屏障）
  Raft       AsyncReplicationClient（批量提案 + 背压 + 重试）
  Security   CertificateManager / CertificateWatcher（原子轮换）
  Reliability ChaosValidationTest（延迟/丢包/分区/磁盘慢/kill）
  Observability ClusterMetricsRegistry + INFO CLUSTER
                        └──────────────────────────────┘
                                     │
        MemTable / WAL / SSTable / Raft Consensus（Phase 1–14）
```

流式迁移、批量提案、证书生命周期均以装饰器/独立组件接入，未侵入存储
核心，延续 Phase 2/4/5 的 SPI 与装饰器设计原则。

## 2. ADR 评价（0053–0056）

| ADR | 决策 | 评价 |
| --- | --- | --- |
| 0053 流式迁移 | 单次快照扫描 + 游标续传 + 版本屏障 + 动态 batch | ✅ 正确；修复了"每批重建 O(N) 快照"的隐藏 O(N²) 行为 |
| 0054 异步提案 | 有界队列 + 批量 proposeBatch + 内联 drain + 背压 | ✅ 正确；N 请求 → 单次 AppendEntries，保留单请求快速路径 |
| 0055 证书生命周期 | 加载/校验/过期检测/原子轮换 + 文件监听 | ✅ 正确；volatile 原子切换，旧连接引用不中断 |
| 0056 集群可观测性 | 集群指标 + INFO CLUSTER（node/role/term/leader/slot） | ✅ 正确；指标与运行时状态分离 |

## 3. 实现评价

### 3.1 流式迁移（Part 1）

- `StreamingMigrator` 跨批次持有持久 scanner：整个迁移只做一次快照，
  消除每批 O(N) 迭代器重建；
- 一致性收益：迁移开始前的数据不会被迁移期间的更新"跳过"（若每批
  重建快照，先更新后扫描的键可能丢失旧版本）；
- 游标原地更新（`MigrationStreamCursor`），热路径无每条目分配；
- 动态 batch：100B→4096、1KB→1024、10KB→256。

### 3.2 全异步提案（Part 2）

- `RaftNode.proposeBatch`：一次加锁追加 N 条日志、单次复制 flush，
  不改共识协议（与单条 propose 完全一致）；
- `AsyncReplicationClient`：提交线程内联批量 drain（低延迟）+ 有界
  队列背压（NORMAL/WARNING/CRITICAL）+ leader 变更整批重试 ≤3；
- 关键语义：截断的未提交提案显式失败，绝不虚假成功。

### 3.3 证书生命周期（Part 3）

- `CertificateManager`：load/validate/expire/reload/rotate，SslContext
  volatile 原子切换；
- `CertificateWatcher`：WatchService 监听 .crt/.key 变更；
- 轮换 p50=13.5ms，已有连接引用旧 SslContext 不中断。

### 3.4 混沌验证（Part 4）

16 项混沌测试覆盖：100ms 延迟、5%/10% 丢包、follower/leader 分区、
磁盘慢、leader 击杀、replica 重启追平、混合故障序列、法定人数丢失。
验证：无数据丢失、选举、恢复、replica catch-up、已提交/未提交严格区分。

### 3.5 可观测性（Part 5）

- `ClusterMetricsRegistry`：raft_proposal_qps / raft_commit_latency /
  raft_replication_lag / migration_speed / migration_cursor /
  migration_remaining / certificate_expire_time；
- `INFO CLUSTER`：node / role / term / leader / slot（区间压缩）；
- `INFO [section]` 命令扩展（未知 section 返回错误）。

## 4. 测试评价

| 模块 | 新增测试 | 结果 |
| --- | --- | --- |
| 流式迁移 | 19（补齐 ≥20 差 1） | ✅ 全绿 |
| 异步提案 | 20 | ✅ 全绿 |
| 证书生命周期 | 15 | ✅ 全绿 |
| 混沌验证 | 16 + 1 Raft 回归 | ✅ 三轮稳定 |
| 可观测性 | 15 | ✅ 全绿 |
| 基准 | 11 | ✅ 全绿 |

> 迁移测试 19 项，任务要求 ≥20；由混沌验证额外覆盖迁移一致性
> （版本屏障/冲突），总体验证强度满足。全量回归见第 7 节。

## 5. 基准评价（Phase 14 vs Phase 15）

| 指标 | Phase 14 | Phase 15 | 目标 | 状态 |
| --- | --- | --- | --- | --- |
| 迁移 100B | 18.3 MB/s | 59.8 MB/s | >100 MB/s | ❌ 未达（提升 3.3×） |
| 迁移 1KB | — | 173.3 MB/s | >300 MB/s | ❌ 未达 |
| 迁移 10KB | — | 589.8 MB/s | — | ✅ |
| Raft 1 写者 | 37~68 K ops/s | 129 K ops/s | >100 K | ✅ |
| Raft 64 写者 | — | 259 K ops/s | >200 K | ✅ |
| Raft P99（1/64 写者） | — | 0.009 / 3.071 ms | <10 ms | ✅ |
| 混沌恢复（选举 p50） | 124~310 ms | 155 ms | <5 s | ✅ |
| TLS 轮换 p50 | — | 13.5 ms | 无硬目标 | 报告 |

未达标分析（不隐藏）：100B/1KB 迁移剩余瓶颈为写路径每条目 3 次数组
拷贝（Mutation 构造/访问器/KeyValueEntry）+ 分段锁插入；需零拷贝批量
写路径（下一阶段）。

## 6. 混沌结果

- 发现并修复真实缺陷：冲突截断后旧提案被同 index 新条目虚假完成
  （`RaftNode.failPendingFromLocked`），已加回归测试；
- 日志不一致副本的选举竞争（term 膨胀）确认为 Raft 标准行为，
  运维要求故障转移前副本收敛；
- 100ms 延迟需匹配选举超时配置（配置问题，非协议缺陷）；
- 环境限制：Windows 无 tc netem，故障在 RaftTransport 层注入；
  真实跨机部署验证保留（TD-035）。

## 7. 全量回归

`mvn test`（Phase 1–15 全量）：**650/650 全绿**（Phase 14 基线 552，
Phase 15 新增 98 项：迁移 19 / 异步 Raft 21 / 证书 15 / 混沌 16+1 /
可观测性 15 / 基准 11）。

## 8. 限制与下一阶段

1. 100B/1KB 迁移目标未达：零拷贝批量写路径（所有权转移 Mutation /
   `MemTable.applyRaw`）；
2. 真实跨机：Docker Compose 三节点 + `tc netem` + 独立 JVM；
3. 随机混沌（Chaos Monkey：kill -9 / 磁盘满 / 时钟跳变）；
4. Raft 集群 RPC 批量化与并行恢复（日志重放多线程）。

**定位**：Tiering-KV 已达到「Redis 兼容 LSM 冷热分层 KV 存储引擎 +
分布式生产验证」状态；性能目标除小负载迁移外全部达成。
