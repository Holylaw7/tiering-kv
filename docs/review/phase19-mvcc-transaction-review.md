# Phase 19 评审报告：MVCC 与事务引擎

Phase 19 · 2026-08-10

## 1. Architecture

```text
Client → TransactionClient → TransactionCoordinator → RegionRouter
        → MVCC Layer（LockTable/WriteRecord/MvccReader/Snapshot/GC）
        → RaftGroup → StorageEngine
```

在既有存储/Raft 之上以 adapter 引入 MVCC 与 Percolator 事务，不破坏
Phase 1–18。

## 2. ADR

| ADR | 决策 |
| --- | --- |
| 0071 MVCC Data Model | [userKey][type][startTS][commitTS] + adapter |
| 0072 Timestamp & HLC | 原子 Oracle + 回拨安全 HLC |
| 0073 Transaction Protocol | Percolator 2PC + 状态机 |
| 0074 Lock & Conflict | LockTable + 写写/读写/锁冲突 |
| 0075 MVCC GC | SafePoint + 保留最新 |
| 0076 Recovery | 超时回滚 / primary 补完 / 无永久锁 |

## 3. Implementation

- TimestampOracle/HybridLogicalClock/MvccKey/MvccEntry/MvccStorageEngine
  （内存版本索引，启动重建）；
- SnapshotReader/LockTable/ConflictDetector + 三类异常；
- Transaction/Prewrite/Commit/Rollback/TransactionManager/Coordinator
  （参与者键归属 + 部分 prewrite 回滚）；
- TxnJournal（InMemory/Raft）；TransactionRecoveryManager；MvccGcManager；
- TransactionMetricsRegistry/MvccMetricsRegistry + Prometheus 导出。

## 4. Tests

新增 226 项（Phase 18 基线 1112）：

| 模块 | 新增 | 结果 |
| --- | --- | --- |
| Timestamp | 15 | ✅ |
| MVCC Storage | 33 | ✅ |
| Snapshot | 21 | ✅ |
| Transaction | 36 | ✅ |
| Conflict | 16 | ✅ |
| Recovery | 17 | ✅ |
| GC | 16 | ✅ |
| Cross-Region | 18 | ✅ |
| Raft 集成 | 20 | ✅ |
| 并发（100 写者+100 读者） | 9 | ✅ |
| 混沌 | 10 | ✅ |
| 指标 | 10 | ✅ |
| 基准 | 5 | ✅ |

全量回归最终统计见合并后报告（目标 >1290）。

## 5. Benchmark

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| MVCC GET | 3.08–4.71M ops/s | >500K | ✅ |
| 单 Region 事务 | 70.8–204.6K txn/s | >100K | ✅（最佳轮） |
| 冲突检测 | 2.14–7.60M ops/s | >500K | ✅ |
| GC | 19–29 MB/s | >100 MB/s | ❌ 未达（TD-041） |

## 6. Chaos

- 已提交事务重启/恢复后不丢；未提交不虚假成功；
- 超时锁恢复无永久锁；快照恢复保留历史版本；
- 跨 Region 分区无交叉污染（参与者键归属）。

## 7. Limitations（不隐藏）

1. GC 未达 100MB/s（19–29）：逐版本删除无批量路径（TD-041）；
2. 单 Region 事务吞吐首轮 70.8K（JIT/GC 波动），最佳轮 204.6K；
3. 内存版本索引增加内存开销（可演进为按需重建/压缩）；
4. Raft 集成限于事务记录日志（proposal 级），未实现 Raft 内预写
   （Percolator 数据即锁即日志的完整路径）；
5. Redis 网关未接 MVCC 层（自动单键事务化留待 Phase 20）。

## 8. Next Phase

- 批量 GC 删除路径（目标 >100MB/s）；Redis GET/SET/DEL 自动事务化；
- Raft 内预写（proposal 携带 prewrite/commit）；锁与快照的跨节点协调；
- MVCC 索引持久化压缩。

**定位**：Tiering-KV 已具备 MVCC + Snapshot Isolation + Percolator 2PC +
恢复 + GC 的分布式事务内核。
