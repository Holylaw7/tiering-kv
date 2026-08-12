# ADR-0151: Autonomous Capacity & Traffic Closure

## Status

Accepted

## Context

Phase 33 的容量建议与流量策略停留在"建议/静态配置"，未形成
"预测 → 建议 → 执行 → 验证"闭环；无护栏的自动扩容有容量风险。

## Decision

1. `capacity/ai/AutonomousCapacityController`：预测 → 建议 → 批准
   （策略）→ CapacityPlanner 执行 → 验证；
2. `gateway/AutonomousTrafficController`：基于预测动态调整
   RegionQuota / TrafficPolicy（限幅 + 熔断 + 回滚）；
3. 护栏：单步调整上限、日调整上限、高水位拒绝执行；
4. 验收：护栏矩阵（越界拒绝/回滚）、执行幂等、失败登记。

## Alternatives

1. 全自动无审批：容量风险不可控；
2. 保持人工建议：闭环未形成。

## Consequences

优点：护栏内自治，容量/流量自适应。

缺点：需要策略配置与审计。

风险：预测偏差由限幅/熔断/回滚兜底。

## Implementation

代码影响范围：`capacity/ai/` + `gateway/` + 测试 +
`docs/capacity/autonomous-closure.md`。
