# ADR-0152: Cross-Cloud Data Mesh with Sovereignty

## Status

Accepted

## Context

Phase 33 联邦查询为单集群/单云内域间聚合；跨云联邦需要数据主权约束，
跨驻留边界查询默认拒绝。

## Decision

1. `datamesh/CloudFederatedExecutor`：域 → 云/地域分片执行；
2. 数据主权校验：跨驻留边界联邦默认拒绝（ComplianceValidator 联动）；
3. 验收：跨云聚合正确 + 主权违规拒绝矩阵。

## Alternatives

1. 不做主权校验：合规风险；
2. 全量复制到单云：实时性与成本差。

## Consequences

优点：跨云实时联邦 + 主权安全。

缺点：跨云执行依赖云元数据。

风险：跨云延迟由超时与重试约束。

## Implementation

代码影响范围：`datamesh/` + `compliance/` 联动 + 测试 +
`docs/datamesh/cross-cloud-federation.md`。
