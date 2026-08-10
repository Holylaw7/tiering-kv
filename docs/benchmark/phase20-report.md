# Phase 20 基准报告：事务生产化与存储优化

Phase 20 · 2026-08-10 · 环境：Windows 11 / Java 21 / 20 cores / 1GB heap

## 1. 方法说明

- 每项指标 3 轮运行，报告 min–max 范围；
- 进程内 MemTable 承载；GC/网关/事务均在存储层路径测量；
- 网关指标为 AutoTransactionExecutor 直连路径（含 2PC 提交），
  不含 TCP/RESP 编解码。

## 2. 指标与目标

| 指标 | 实测范围 | 目标 | 状态 |
| --- | --- | --- | --- |
| MVCC 批量 GC | 107–285 MB/s | >100 MB/s | ✅ |
| 网关 GET（自动事务快照读） | 2.0–6.9M ops/s | >500K ops/s | ✅ |
| 网关 SET（单键 2PC） | 141–389K ops/s | >100K ops/s | ✅ |
| 单 Region 事务 | 324–651K txn/s | >200K txn/s | ✅ |
| 跨 Region 2PC | 62–158K txn/s | >50K txn/s | ✅ |
| 事务恢复（1000 悬挂锁） | 1–4 ms | <1s | ✅ |

## 3. 与 Phase 19 对比

| 指标 | Phase 19 | Phase 20 | 提升 |
| --- | --- | --- | --- |
| MVCC GC | 19–29 MB/s | 107–285 MB/s | 约 5–10x（TD-041 关闭） |
| 单 Region 事务 | 70.8–204.6K txn/s | 324–651K txn/s | 约 2–3x |
| 跨 Region 2PC | 41.7–50.1K txn/s | 62–158K txn/s | 约 1.5–3x |

## 4. 优化说明

- GC：内存索引直接规划（不再全表扫描）+ 每 key 一次索引重建 +
  按段单锁批量物理删除 + 并行 worker；
- 网关：GET=快照读（readTS=max(HLC, oracle 水位)），SET/DEL=单键
  事务，MSET=跨 shard 2PC；
- 锁过期：改为墙上时钟（修复 HLC 尺度与 currentTimeMillis 错配）。

## 5. 复现

```bash
mvn -Dtest=Phase20MvccBenchmarkTest,MvccGcPerformanceTest test
```

输出前缀：`PHASE20-BENCH`。

## 6. 未达标项

无。GC/网关/事务/恢复全部达到 Phase 20 目标；如实记录各轮范围。
