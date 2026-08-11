# ADR-0123: Five-Region Topology and Global Read

## Status

Accepted

## Context

Phase 28 容灾为两地三中心。五中心（2 主 + 2 备 + 1 仲裁）需要更复杂的
拓扑与全球一致性读（就近 + 水位校验）。

## Decision

扩展 `dr/`：

1. `DrTopology` 支持五角色扩展（multi-primary / backup / arbiter）；
2. `GlobalReadRouter`：就近读 + 一致性水位校验（readTS <= 已复制水位）；
3. 模式：strong（leader 读）/ bounded（水位内读）可配置；
4. 故障矩阵演练与 RTO/RPO/陈旧度报告。

## Alternatives

1. 仅 leader 读：延迟高；
2. 无水位校验：读到陈旧数据。

## Consequences

优点：读延迟与一致性可权衡、故障矩阵可演练。

缺点：bounded 模式需要滞后指标支撑。

风险：水位维护需与复制管道联动。

## Implementation

代码影响范围：`dr/` + `GlobalReadRouter` + 测试 +
`docs/dr/{five-region-guide,global-read-design}.md`。
