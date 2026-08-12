# ADR-0217: Autonomous PD & Global Autonomy Integration

## Status

Accepted

## Context

自治 PD 调度与全球自治（容量/流量/拓扑）独立运行；需要联动闭环。

## Decision

1. `cluster/scheduler/GlobalAutonomyPdIntegration`：拓扑变化 → 调度
   计划 → 护栏内执行；
2. 与 AutonomousPdScheduler / TopologyDiscovery / 自治控制器联动；
3. 只调策略，禁止放宽一致性约束；
4. 验收：联动矩阵 + 护栏 + 回滚。

## Alternatives

1. 独立运行：资源竞争；
2. 无护栏联动：调度风暴。

## Consequences

优点：调度与自治协同。

缺点：需要联动输入。

风险：误联动由护栏与回滚兜底。

## Implementation

代码影响范围：`cluster/scheduler/` + 测试 +
`docs/cluster/autonomous-integration.md`。
