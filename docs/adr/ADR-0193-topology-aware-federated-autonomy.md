# ADR-0193: Topology-Aware Federated Autonomy

## Status

Accepted

## Context

Phase 39 的多智能体聚合为同步平均（TD-070），未考虑地域拓扑；
就近地域应优先聚合。

## Decision

1. `capacity/ai/TopologyFederatedAutonomy`：地域拓扑（就近分组）→
   分层聚合（本地组 → 全局）；
2. 拓扑权重可配置，聚合限幅 + 安全上下界 + 审计；
3. 与 MultiAgentAutonomy 联动；
4. 只调权重/聚合策略，禁止放宽安全核心约束；
5. 验收：拓扑矩阵 → 分层权重、组/全局一致性、越界拒绝。

## Alternatives

1. 同步平均：忽略拓扑；
2. 全局中心聚合：跨地域带宽成本。

## Consequences

优点：就近聚合，收敛更快、带宽更低。

缺点：拓扑配置需维护。

风险：拓扑偏差由限幅与审计兜底。

## Implementation

代码影响范围：`capacity/ai/` + 测试 +
`docs/capacity/topology-autonomy.md`。
