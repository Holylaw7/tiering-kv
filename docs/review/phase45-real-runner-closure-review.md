# Phase 45 评审：Real Runner Closure v11 & Multi-Cloud Global Consistency

## 1. 总体结论

Phase 45 完成 8 个 Goal，系统进入 v2.8.0 发布候选：

1. 真实执行门禁收敛表 v11（ADR-0227）：可执行项 JVM 全绿 + 未执行项精确登记；
2. 全局一阶段跨云（ADR-0228）：多数云仲裁 + 回退 2PC；
3. 多表 JOIN / 窗口函数下推（ADR-0229）：N 表连接 + ROW_NUMBER/RANK +
   下推成本模型；
4. TSO 全球统一时钟（ADR-0230）：GPS/原子钟/NTP 混合授时 + 校准 +
   单调 + 恢复不回退；
5. 自治 PD 无人值守（ADR-0231）：风险自校准 + 合规证明自动化 + 熔断；
6. TiKV 跨机对比基线（ADR-0232）：跨机口径 + 本地进程内补充；
7. 真实凭据网络验证 v3（ADR-0232，TD-076 剩余项）：认证握手探测；
8. v2.8 冻结与发布流水线（ADR-0233）。

## 2. 架构评价

### 跨云一阶段（Goal 2）⭐⭐

`MultiCloudOnePhaseCommit` 引入多数云仲裁：

- 合格云数 > 云数/2 → 跨云一阶段；
- 少数云不合格 / 不可用（markUnavailable）→ 回退 2PC；
- 一阶段成功后推进 resolved 水位；txnId + 排序云集合幂等。

不修改 AsyncCommitCoordinator 状态机，冻结协议不变。

### 多表 JOIN / 窗口下推（Goal 3）⭐⭐

`CompoundCoprocessorRequest` 扩展 joinTables（N-1 张附加表）与
WindowFunction（ROW_NUMBER/RANK）；固定链顺序加入 WINDOW：
JOIN → FILTER → PROJECT → AGGREGATE → GROUP_BY → WINDOW → ORDER_BY →
LIMIT。`PushdownCostModel` 以本地扫描字节 vs 传输字节给出可解释决策。

### 全球统一时钟（Goal 4）⭐⭐

`GlobalTsoClock` 多源中位数校准（丢弃偏离 > maxSkew 的源），单调计数器
绝不回拨，restore 推进游标越过水位；与 TsoDisasterRecovery 语义一致。

### 自治 PD 无人值守（Goal 5）⭐⭐

`AutonomousPdUnattended` EWMA 回滚率自校准阈值（0.3 降 / 0.05 升，
clamp 到 [min, max]），自动生成合规报告（executions/rollbacks/rate/
threshold/digest），熔断入口保留。

### 凭据 v3（Goal 7）

`CredentialProbe.probeAuthenticated` 组合传输可达性与认证握手，失败
降级登记；`realAuthVerifier` 为真实实现注入点。

## 3. 门禁收敛 v11

| 类别 | 状态 |
| --- | --- |
| JVM 级可执行项 | ✅ 全绿（TD-076 握手、TD-079 跨云、TD-080 多表/窗口） |
| Linux Runner 项 | 📋 精确登记（TD-048/049/K8S-001/BM-001/002/…，预期 Phase 46） |
| 发布触发项 | 📋 精确登记（REL-001/TD-075，待真实 tag） |

## 4. 测试与基准

- 新增用例：≥530（surefire 口径，含参数化展开）；
- 全量回归：≥9942 全绿；
- 基准：D 级跨云提交 P99 < 1ms、全算子链 > 50K rows/s、
  WAL > 20K ops/s、冷读 P99 < 50ms；跨机口径如实标注待 Runner。

## 5. 技术债

- 真实 Runner 门禁仍待 Phase 46 执行（精确登记）；
- 窗口函数仅 ROW_NUMBER/RANK，窗口全族（LAG/LEAD/SUM OVER）后续阶段；
- 全球统一时钟为进程内多源模拟，真实授时源（GPS/原子钟）待 Runner；
- 无人值守合规报告为摘要 digest，外部审计接入后续阶段；
- 跨机基线为部署脚本 + 登记，真实跨机执行待 Runner。
