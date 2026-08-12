# ADR-0162: SLA & SLO Management

## Status

Accepted

## Context

系统已有延迟/吞吐指标与告警，但缺少 SLO 定义、滚动窗口达成率计算与
违约告警，无法支撑企业级 SLA 承诺。

## Decision

1. `operations/slo/SloDefinition`：指标 + 目标值 + 窗口；
2. `operations/slo/SloManager`：达成率计算（滚动窗口）+ 状态；
3. `operations/slo/SloAlert`：SLO 违约告警；
4. 与 Phase28Metrics 联动；
5. 验收：达成率矩阵 + 窗口滚动 + 告警阈值。

## Alternatives

1. 仅原始指标：无法度量 SLA；
2. 外部监控平台：依赖重。

## Consequences

优点：SLO 可量化、可告警。

缺点：窗口与目标需业务配置。

风险：口径变化由参数化测试兜底。

## Implementation

代码影响范围：`operations/slo/` + 测试 +
`docs/operations/slo-sla.md`。
