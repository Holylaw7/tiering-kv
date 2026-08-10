# Phase 20 评审报告：事务生产化与存储优化

Phase 20 · 2026-08-10

## 1. 总体结论

Phase 20 将 Phase 19 的“功能完整事务内核”推进到“生产可用”：

- TD-041 关闭：批量 GC 达 107–285MB/s（>100 ✅）；
- TD-042 关闭：Redis 网关自动事务（GET/SET/DEL/MGET/MSET）；
- MVCC 索引持久化（快照保存/恢复/增量重建）；
- 事务状态经 Raft 持久化 + 恢复重放（无幻影提交 / 无丢失提交）；
- 可观测性：INFO TRANSACTION/MVCC + Prometheus 指标补齐。

## 2. ADR

| ADR | 决策 |
| --- | --- |
| 0078 | 批量 GC：索引规划 + 分段批量删除 + 并行 worker |
| 0079 | 网关自动事务：GET 快照读 / SET/DEL 单键事务 / MSET 2PC |
| 0080 | 持久化 MVCC 索引：MAGIC/VERSION/CRC + 增量重建 |
| 0081 | 事务日志 Raft 持久化：COMMIT 决策先落盘，恢复补完 |
| 0082 | 跨机验证：Docker + tc netem；环境受限时本地等价 + TD |

## 3. 实现

- `mvcc/gc`：BatchGcExecutor（扫描→分组→规划→并行批量删除）、
  GcConfig（batch-size / worker-count / max-memory）；
- `StorageEngine.removeAll` / `MemTable.removeAll`：按段单锁批量物理删除；
- `cluster/gateway`：AutoTransactionExecutor、TransactionCommandHandler、
  RedisClusterGateway 可选注入（无 MVCC 时 Phase 18 行为不回归）；
- `mvcc/index`：MvccIndexSnapshot / Writer / Reader / PersistentMvccIndex；
- `mvcc`：PersistentTxnJournal（本地追加 + Raft 提案重试）、
  TxnStateRecord（PREWRITE/COMMIT/ROLLBACK + 变更集）、
  TxnRecoveryReplay（幂等补完）、TransactionCoordinator 决策落盘；
- 锁过期修复：LockRecord 增加墙上时钟 createdAtMillis，
  修复 HLC 尺度与 currentTimeMillis 错配导致的锁永不过期。

## 4. 测试

新增 181 项测试方法（目标 ≥180 ✅）：

| 分类 | 数量 | 结果 |
| --- | --- | --- |
| GC | 30 | ✅ |
| 网关事务 | 35 | ✅ |
| MVCC 持久化 | 25 | ✅ |
| 事务日志 | 30 | ✅ |
| 混沌 | 30 | ✅ |
| 可观测性 | 16 | ✅ |
| 基准 | 5 | ✅ |
| 集成 | 10 | ✅ |

全量回归：**1523/1523 全绿（0 failures）**。

## 5. 基准

见 [phase20-report.md](../benchmark/phase20-report.md)：
GC 107–285MB/s、网关 GET 2.0–6.9M、SET 141–389K、
单区事务 324–651K、跨区 62–158K、恢复 1–4ms，全部达标。

## 6. 跨机验证（Goal 5）

- Docker daemon 已启动；3 节点 compose 构建上下文与 NET_ADMIN 已修正；
- 容器内 `mvn dependency:go-offline` 失败（疑似 Maven Central 网络受限），
  跨机 tc netem 验证未执行；
- 降级：本地进程内混沌（Phase20TransactionChaosTest 30 项）覆盖
  崩溃点/分区/重启/重放/无永久锁；
- 登记 TD-043：MVCC/事务尚未接入 Multi-Raft Region 网络路径，
  跨机事务验证待 Phase 21（网关 + Region 路由接入后）执行。

## 7. 局限（不隐藏）

1. TD-040：Linux/Docker 跨机混沌未完成（容器内 Maven 网络受限）；
2. TD-043：事务网关尚未与 Multi-Raft/Region 路由网络化集成；
3. 索引快照不包含锁表：恢复后悬挂锁只能靠超时/日志重放判定；
4. COMMIT 决策以本地落盘为权威，Raft 为传播通道（至少一次语义）。
