# ADR-0211: Autonomous Scheduling & Topology Discovery

## Status

Accepted

## Context

PD 等价调度为静态计划生成；需要与自治闭环联动执行，并自动发现全球
拓扑。

## Decision

1. `cluster/scheduler/AutonomousPdScheduler`：调度计划 → 护栏内执行
   （epoch + 限幅 + 回滚）；
2. `cluster/topology/TopologyDiscovery`：节点心跳 → 拓扑推断（地域/
   可用区/延迟分组）；
3. 与 Placement/Rebalance/Quota + TopologyFederatedAutonomy 联动；
4. 只调策略，禁止放宽一致性约束；
5. 验收：调度执行矩阵 + 护栏矩阵 + 发现矩阵。

## Alternatives

1. 静态调度：不随负载变化；
2. 手工拓扑：维护成本高。

## Consequences

优点：调度自治 + 拓扑自动发现。

缺点：需要心跳/策略输入。

风险：误调度由护栏与回滚兜底。

## Implementation

代码影响范围：`cluster/scheduler/` + `cluster/topology/` + 测试 +
`docs/{cluster/autonomous-scheduling,cluster/topology-discovery}.md`。
