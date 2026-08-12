# Phase 36 评审报告：Gate Convergence & Self-Learning Autonomy

## 1. 总体结论

Phase 36 完成门禁收敛与自学习自治：

```text
真实执行门禁收敛表 v2（JVM 级先行）
      ↓
全球自治自学习围栏 → CDC 增量物化 → 合规持续证明
      ↓
多云成本竞价调度 → 网络策略即代码 → SLO 预算容量
      ↓
v1.9 冻结 + 发布流水线
```

全量新增测试 **374 项**（surefire 口径），全量回归
**5660/5660 全绿**（目标 ≥5656 ✅，+6 容器门控跳过）。

## 2. Goal 1 — 真实执行门禁收敛（ADR-0164）

- `Phase36ProductionGateTest` 15 项 + `Phase36EdgeMatrixTest`
  （参数化矩阵）覆盖全部新能力；
- 收敛表 v2 登记：TD-048/049、K8S-001、REL-001、BM-001/002、
  TD-051/054/059 仍待 Linux Runner（可执行项全绿 + 未执行项精确登记）。

## 3. Goal 2 — 自学习围栏（ADR-0165）

- `SelfLearningFence`：成功放宽 / 失败收紧 / 回滚熔断；
- 参数变化限幅（relaxStep/tightenStep）、安全上下界（Bounds）、
  审计日志；只调整策略参数，不放宽安全核心约束。

## 4. Goal 3 — CDC 增量物化（ADR-0166）

- `CdcMaterializedViewRefresher`：变更流（INSERT/UPDATE/DELETE）→
  增量聚合；失败回退全量刷新并标记 stale；
- `MaterializedViewManager.updateSnapshot` 提供增量快照写入。

## 5. Goal 4 — 合规持续证明（ADR-0167）

- `AttestationChain`：SHA-256 哈希链（index|regulation|version|
  violations|prevHash）；
- 验证 API + 篡改检测 + 并发安全（append 同步化）。

## 6. Goal 5 — 多云成本调度（ADR-0168）

- `CloudCostScheduler`：最低成本选择 + 数据主权 / 配额 / SLO 约束；
- 无满足候选返回空，杜绝违约调度。

## 7. Goal 6 — 网络策略即代码（ADR-0169）

- `NetworkPolicyDsl`：声明式 allow/deny 解析（注释/空白/非法拒绝）；
- `PolicyCompiler`：DSL → IsolationPolicy，编译幂等。

## 8. Goal 7/8 — SLO 预算 + v1.9（ADR-0170）

- `SloBudgetPlanner`：达成率 → 余量/缺口 → 扩容建议（SCALE_UP /
  MAINTAIN + maxNodes 上限）；
- release.yml 扩展 v1.9.0 标签 + Phase36BenchmarkTest 接入。

## 9. 测试与基准

| 模块 | 新增（@Test 口径） |
| --- | ---: |
| 自学习围栏 | 21 |
| CDC 增量物化 | 26 |
| 合规持续证明 | 22 |
| 多云成本调度 | 25 |
| 网络策略即代码 | 27 |
| SLO 预算容量 | 20 |
| v1.9 基准/门禁 | 27 |
| 参数化边缘矩阵 | 49 |
| **合计** | **217** |

surefire 参数化展开后新增 **374 项**。

进程内基准（如实记录）：

| 指标 | 结果 |
| --- | --- |
| 自学习围栏 | 43K~3.33M ops/s |
| CDC 增量物化 | 100K~769K ops/s |
| 合规证明链 | 17.9K~128K ops/s |
| 多云调度 | 333K~10M ops/s |
| 策略编译 | 143K~1M rules/s |
| SLO 预算规划 | 100K 次 / 3 ms |

## 10. 技术债（新增/延续）

- TD-060：真实执行门禁（CI 容器/磁盘混沌/kind/release/跨机跨地域）
  仍待 Linux Runner；
- TD-061：自学习围栏为单指标反馈，未做多目标优化；
- TD-062：CDC 增量物化未持久化增量状态（重启需全量回退）。

延续：TD-048/049、K8S-001、REL-001、BM-001/002、TD-051~059。
