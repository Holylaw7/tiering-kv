# Phase 43 评审：Global Scale & Production Baseline Convergence

## 1. 总体结论

Phase 43 完成 8 个 Goal，系统进入 v2.6.0 发布候选：

1. 真实执行门禁收敛表 v9（ADR-0213）：可执行项 JVM 全绿 + 未执行项精确登记；
2. 跨区一阶段提交（ADR-0214，TD-079 关闭方向）：主副本资格 → 一阶段 /
   失败回退 2PC；
3. Coprocessor 多算子联合下推（ADR-0215，TD-080 关闭方向）：
   FILTER → PROJECT → AGGREGATE 链；
4. TSO 集群化（ADR-0216）：批量分配 + 单调 + 恢复不回退；
5. 自治 PD 与全球自治联动（ADR-0217）：拓扑变化 → 计划 → 护栏执行 + 回滚；
6. 生产级 Benchmark 基线（ADR-0218）：A/B/C 三级 + TiKV 对比口径；
7. 真实凭据验证（ADR-0218，TD-076 关闭方向）：S3/Spot 探测 + 降级登记；
8. v2.6 冻结与发布流水线（ADR-0219）。

## 2. 架构评价

### 跨区一阶段（Goal 2）⭐

`CrossRegionOnePhaseCommit` 不侵入 AsyncCommitCoordinator 状态机：

- 注册主副本资格（region → onePhaseEligible）；
- 全部区域主副本合格 → 一阶段；任一不合格 → 回退 2PC；
- `commitTwoPhase` 显式两阶段，幂等由调用方保证。

符合「只扩展、不改冻结协议」原则：新能力 additive，v1.0–v2.5 冻结协议不变。

### 多算子联合下推（Goal 3）⭐⭐

`CompoundCoprocessorRequest` 携带算子链，`CoprocessorExecutor.executeCompound`
按 FILTER → PROJECT → AGGREGATE 顺序应用；与上层 SQL 结果一致由
`chainResultConsistentWithUpperSql` / `filterAfterProjectMatchesUpperSql`
用例锁定。避免存储层直接暴露算子编排给命令层。

### TSO 集群化（Goal 4）⭐⭐

`TsoService` 基于 AtomicLong：

- 批量分配返回 `[start, end]`，水位随分配单调推进；
- `restore(persistedWatermark)` 只推进不回退，且推进分配游标越过恢复水位，
  保证重启后新分配严格大于已持久化水位；
- 并发单调由多线程分配用例验证。

修复点：恢复语义最初只推进水位，新分配可能低于恢复水位；本阶段改为
水位 + 游标双推进（`restoreAdvancesAllocationCounter` 覆盖）。

### 自治 PD 与全球自治联动（Goal 5）⭐⭐

`GlobalAutonomyPdIntegration` 形成闭环：

```text
TopologyDiscovery（心跳/健康）
        ↓ 拓扑变化检测（版本递增）
RebalanceScheduler（负载 → 计划）
        ↓
护栏：政策权重冻结 / 地域 quorum / AZ 分散 / 单轮上限 / 熔断 / 执行钩子
        ↓
AutonomousPdScheduler（护栏内执行）
        ↓ 失败 → 回滚本轮 + 审计
```

政策冻结只调策略（TIGHTEN 权重 > 0.6 冻结本轮），不放松任何一致性约束。

### 凭据探测（Goal 7）

`CredentialProbe` 支持 REAL / SIMULATED / AUTO 三模式：

- REAL：端点探针 + 凭据非空校验，失败登记 `ProbeFailure`；
- SIMULATED：无真实凭据时的确定性回退；
- AUTO：按端点 + 凭据是否存在切换 REAL / SIMULATED。

探测失败必须降级 + 登记，禁止伪报可用。

## 3. 门禁收敛 v9

| 类别 | 状态 |
| --- | --- |
| JVM 级可执行项 | ✅ 全绿（TD-076 探测、TD-079、TD-080） |
| Linux Runner 项 | 📋 精确登记（TD-048/049/K8S-001/BM-001/002/…） |
| 发布触发项 | 📋 精确登记（REL-001/TD-075，待真实 tag） |

未执行项全部记录阻塞原因与预期消除阶段（Phase 44），禁止伪报。

## 4. 测试与基准

- 新增用例：≥510（surefire 口径，含参数化展开）；
- 全量回归：≥8867 全绿；
- 基准：A 级内存 GET P99 < 5ms、B 级命令链 P99 < 10ms、
  C 级全链路（WAL + SSTable + mmap）P99 < 50ms，本地进程内口径注明；
  TiKV 对比为公开数据参考，非跨机实测。

## 5. 技术债

- TD-076/079/080：JVM 级关闭方向完成，真实网络/Runner 待 Phase 44；
- 跨区一阶段回退 2PC 幂等由调用方保证，尚未做分布式重试协议；
- TSO 跨地域容灾（多地时间戳）留待 Phase 44+；
- Coprocessor JOIN / GROUP BY 全算子下推留待后续阶段。
