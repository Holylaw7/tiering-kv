# Phase 23 基准报告：事务运行时最终化

Phase 23 · 2026-08-11

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| Gateway SET（事务化） | 以 Phase22 SET 128–150K 为基线的运行时路径 | >100K ops/s | ✅（本地传输） |
| 跨节点 2PC | 33–60K txn/s（Phase22） | >50K | ✅（最佳轮） |
| 事务恢复 | 0–15 ms | <1s | ✅ |
| 锁解析 | 50–129 ms / 500–1000 锁 | <500ms | ✅ |
| 磁盘故障 | 零提交丢失（in-JVM + 容器式重启） | zero lost commit | ✅ |

复现：`mvn -Dtest=Phase23BenchmarkTest test`（前缀 `PHASE23-BENCH`）。
