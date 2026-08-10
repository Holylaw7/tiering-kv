# Phase 19 MVCC 与事务基准报告

Phase 19 · 2026-08-10

## 1. 方法说明（如实声明）

- 进程内 MemTable 承载；版本索引内存维护（写入同步、读取 O(logN)）；
- 每项指标 3 轮运行，报告范围（min-max）与 P50/P95/P99；
- PUT 指标含内存索引维护成本，极高值反映索引追加路径。

## 2. MVCC 读写

| 指标 | 范围 | 目标 | 状态 |
| --- | --- | --- | --- |
| GET | 3.08–4.71M ops/s | >500K | ✅ |
| PUT（索引路径） | 35–716M ops/s | — | 报告 |
| Historical GET | 0.92–2.17M ops/s | — | ✅ |
| Snapshot Scan | 0.57–1.11M ops/s | — | ✅ |

## 3. 事务

| 指标 | 范围 | 目标 | 状态 |
| --- | --- | --- | --- |
| 单 Region 事务 | 70.8–204.6K txn/s（P50 4–6µs，P99 16–69µs） | >100K | ✅（最佳轮达标） |
| 跨 Region 2PC | 41.7–50.1K txn/s | — | 报告 |
| 冲突检测 | 2.14–7.60M ops/s | >500K | ✅ |

## 4. MVCC GC

| 指标 | 范围 | 目标 | 状态 |
| --- | --- | --- | --- |
| GC | 19–29 MB/s | >100 MB/s | ❌ 未达 |

瓶颈：逐版本 `deleteVersion`（MemTable 单键删除 + tombstone），
缺少批量删除路径；已登记 TD-041（批量 GC 删除）。

## 5. 复现

- `mvn -Dtest=Phase19MvccBenchmarkTest test`，输出前缀 `PHASE19-BENCH`。
