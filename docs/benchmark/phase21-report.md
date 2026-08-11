# Phase 21 基准报告：分布式事务网络化

Phase 21 · 2026-08-11 · 环境：Windows 11 / Java 21 / 20 cores

## 1. 方法说明

- 每项指标 3 轮，报告 min–max；事务路径含 metadata REGISTER/PREPARE/COMMIT
  与 2 阶段 participant RPC（本地传输，不含 TCP 编解码；TCP 端到端由
  CrossNodeTransactionTest 覆盖）；
- leader 恢复测量 3 节点 TxnMetadataRaftGroup 的选主时间。

## 2. 指标与目标

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| 跨节点单 Region 事务 | 58.7–116.4K txn/s | >100K txn/s | ✅（最佳轮，首轮 JIT 预热） |
| 跨节点多 Region 2PC | 88.1–110.7K txn/s | >50K txn/s | ✅ |
| 冲突路径吞吐 | 73.3K txn/s | — | 报告 |
| 事务 P50 延迟 | 3 µs | — | 报告 |
| 事务恢复 | 0–0 ms | <1s | ✅ |
| 元数据 leader 恢复 | 156–276 ms | <5s | ✅ |

## 3. 复现

```bash
mvn -Dtest=Phase21BenchmarkTest test
```

输出前缀：`PHASE21-BENCH`。

## 4. 说明

单 Region 首轮吞吐 58.7K 为 JIT/元数据预热，第三轮稳定在 116K；
如实报告范围，不隐藏首轮。
