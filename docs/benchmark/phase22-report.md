# Phase 22 基准报告：事务可靠性与生产运行时

Phase 22 · 2026-08-11 · Windows 11 / Java 21 / 20 cores

## 1. 方法

- 每项 3 轮，报告 min–max；SET/GET 走 DistributedTxnRouter（本地传输）；
- 恢复与锁解析为 1000 事务/锁规模。

## 2. 指标

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| Redis SET（事务化） | 128.8–150.8K ops/s | >50K | ✅ |
| Redis GET（快照读） | 3.9–25.1M ops/s | >500K | ✅ |
| 跨 Region 事务 | 33.6–59.7K txn/s | >50K | ✅（最佳轮） |
| 事务恢复 | 0–15 ms | <1s | ✅ |
| 锁解析（1000 锁） | 50–129 ms | <500ms | ✅ |

## 3. 复现

```bash
mvn -Dtest=Phase22BenchmarkTest test
```

输出前缀：`PHASE22-BENCH`。

## 4. 说明

跨区首轮 33.6K 为 JIT 预热；最佳轮 59.7K 达标。SET 为完整 2PC 路径
（含元数据 REGISTER/PREPARE/COMMIT），不含 TCP 编解码。
