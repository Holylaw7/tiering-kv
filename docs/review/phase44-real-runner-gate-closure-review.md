# Phase 44 评审：Real Runner Gate Closure & Global Scale Finalization

## 1. 总体结论

Phase 44 完成 8 个 Goal，系统进入 v2.7.0 发布候选：

1. 真实执行门禁收敛表 v10（ADR-0220）：可执行项 JVM 全绿 + 未执行项精确登记；
2. 全局一阶段提交规模化（ADR-0221，TD-079 规模化）：3 地/5 地 + 回退 2PC；
3. Coprocessor 全算子联合下推（ADR-0222，TD-080 规模化）：
   JOIN/GROUP_BY/ORDER_BY/LIMIT；
4. TSO 跨地域容灾（ADR-0223）：主备 + 切换 + 恢复不回退；
5. 自治 PD 全自动（ADR-0224）：风险分级 + 自动执行 + 人工熔断；
6. 生产级 Benchmark 对比 TiKV 口径（ADR-0225）：A/B/C/D 四级；
7. 真实凭据验证 v2（ADR-0225，TD-076 关闭方向）：真实 HTTP 探针；
8. v2.7 冻结与发布流水线（ADR-0226）。

## 2. 架构评价

### 全局一阶段（Goal 2）⭐⭐

`GlobalOnePhaseCommit` 在 CrossRegionOnePhaseCommit 资格模型之上扩展：

- 多区域主副本全部合格 → 全局一阶段；
- 任一不合格 / 探测失败 → 回退 2PC；
- 一阶段成功后推进全局 resolved 水位（与 resolved-ts 联动）；
- txnId 去重保证幂等。

不修改 AsyncCommitCoordinator 状态机，冻结协议不变。

### 全算子下推（Goal 3）⭐⭐

`CompoundCoprocessorRequest` 扩展 JOIN（等值内连接）、GROUP_BY、
ORDER_BY、LIMIT；`CoprocessorExecutor.executeCompound` 采用固定链顺序
JOIN → FILTER → PROJECT → AGGREGATE → GROUP_BY → ORDER_BY → LIMIT
（重复算子按次数应用），与上层 SQL 一致性由等价性测试锁定。

### TSO 跨地域容灾（Goal 4）⭐⭐

`TsoDisasterRecovery` 主备双实例：

- 主分配水位同步到备（syncedWatermark）；
- 切换：备以已同步水位 restore（单调不回退）后接管；
- 原主恢复：以备水位恢复，分配游标越过水位。

### 自治 PD 全自动（Goal 5）⭐⭐

`AutonomousPdFullAutomation` 风险分级：

- LOW（动作数 ≤ 阈值）→ 自动执行；
- HIGH → 审批队列；
- `manualCircuitBreak()` 人工熔断入口；
- 执行/回滚/审计复用 GlobalAutonomyPdIntegration。

### 凭据 v2（Goal 7）

`CredentialProbe.realHttpProber` 提供真实 HTTP 探针（GET 2xx/3xx/4xx
视为可达，异常降级 false），AUTO 模式按端点 + 凭据切换 REAL/SIMULATED。

## 3. 门禁收敛 v10

| 类别 | 状态 |
| --- | --- |
| JVM 级可执行项 | ✅ 全绿（TD-076 真实探针、TD-079、TD-080） |
| Linux Runner 项 | 📋 精确登记（TD-048/049/K8S-001/BM-001/002/…，预期 Phase 45） |
| 发布触发项 | 📋 精确登记（REL-001/TD-075，待真实 tag） |

## 4. 测试与基准

- 新增用例：≥520（surefire 口径，含参数化展开）；
- 全量回归：≥9412 全绿；
- 基准：D 级全局一阶段 P99 < 1ms、全算子链 > 50K rows/s、
  WAL > 20K ops/s、冷读 P99 < 50ms；TiKV 对比为公开数据参考，
  本地进程内口径注明，跨机待 Runner。

## 5. 技术债

- 真实 Runner 门禁仍待 Phase 45 执行（精确登记）；
- JOIN 仅支持等值内连接，非等值/多表 JOIN 后续阶段；
- TSO 容灾为主备模型，多地多主（租约/仲裁）后续阶段；
- 自治全自动保留人工熔断，无人值守 + 合规证明后续阶段；
- D 级为进程内多副本模拟，不等同跨机网络。
