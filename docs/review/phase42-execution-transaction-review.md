# Phase 42 评审报告：Execution Convergence & Transaction Depth

## 1. 总体结论

Phase 42 完成执行收敛与事务深度：

```text
真实执行门禁收敛表 v8（JVM 级先行）
      ↓
Leveled Compaction 执行 → 悲观事务 → Async Commit + resolved-ts
      ↓
Coprocessor SQL 下推 → 自治 PD 调度 → 拓扑自发现
      ↓
v2.5 冻结 + 发布流水线
```

全量新增测试 **502 项**（surefire 口径），全量回归
**8357/8357 全绿**（目标 ≥8355 ✅，+6 容器门控跳过）。

## 2. Goal 1 — 门禁收敛 v8（ADR-0206）

- `Phase42ProductionGateTest` 14 项 + `Phase42EdgeMatrixTest`
  （参数化矩阵）覆盖全部新能力；
- 收敛表 v8：TD-048/049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059/060/063/066/069/072/075 仍待 Linux Runner（精确登记）。

## 3. Goal 2 — Leveled 执行（ADR-0207，TD-077 关闭方向）

- `LeveledCompactionExecutor`：计划 → 合并（latest wins + tombstone +
  TTL 清理）→ 层级落盘 + summarize；
- 零回退（SSTable 格式兼容）。

## 4. Goal 3 — 悲观事务（ADR-0208）

- `PessimisticTransaction`：提前加锁 + 冲突检测 + 死锁超时 +
  读写可见性。

## 5. Goal 4 — Async Commit + resolved-ts（ADR-0209）

- `AsyncCommitCoordinator`：单区一阶段 / 多区回退 2PC；
- `ResolvedTimestampService`：CAS 单调推进。

## 6. Goal 5 — Coprocessor 下推（ADR-0210）

- `CoprocessorRequest/Executor`：FILTER / PROJECT / AGGREGATE +
  范围谓词，与上层 SQL 结果一致。

## 7. Goal 6/7 — 自治调度 + 拓扑发现（ADR-0211）

- `AutonomousPdScheduler`：护栏内执行（单轮上限 + 熔断）；
- `TopologyDiscovery`：心跳 → 地域/AZ 分组 + 健康判定 + 剔除。

## 8. Goal 8 — v2.5 冻结（ADR-0212）

- release.yml 扩展 v2.5.0 标签 + Phase42BenchmarkTest 接入。

## 9. 测试与基准

| 模块 | 新增（@Test 口径） |
| --- | ---: |
| Leveled 执行 | 13 |
| 悲观事务 | 19 |
| Async Commit + resolved-ts | 13 |
| Coprocessor 下推 | 20 |
| 自治调度 + 拓扑发现 | 24 |
| v2.5 基准/门禁 | 22 |
| 参数化边缘矩阵 | 90 |
| **合计** | **201** |

surefire 参数化展开后新增 **502 项**。

进程内基准（如实记录）：

| 指标 | 结果 |
| --- | --- |
| Async Commit | 1M~10M ops/s |
| 悲观锁 | 1M~10M ops/s |
| Coprocessor | 500K~10M rows/s |
| Leveled 执行 | 500K~2.5M/s |
| 自治 PD 调度 | 58.8K~196K ops/s |
| 拓扑发现 | 10K 轮 / 79 ms |

## 10. 技术债（新增/延续）

- TD-078：真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域）
  仍待 Linux Runner；
- TD-079：async commit 为单区一阶段，跨区一阶段未做；
- TD-080：Coprocessor 为单算子下推，多算子联合未做。

延续：TD-048/049、K8S-001、REL-001、BM-001/002、TD-051~077。
