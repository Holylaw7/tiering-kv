# ADR-0143: Global Leader Selection & Data Sovereignty

## Status

Accepted

## Context

全球多活需要地域故障自动选主/流量切换（防脑裂）；跨云部署需要数据
驻留合规（违规迁移/复制拒绝）。

## Decision

1. `replication/active/LeaderSelector`：健康探测 + 自动选主 + 写路由
   切换；仲裁兜底防脑裂；
2. `compliance/`：DataResidencyPolicy + ComplianceValidator，跨驻留
   边界复制/迁移默认拒绝；
3. 验收：故障矩阵切换正确、策略矩阵违规拒绝。

## Alternatives

1. 手动切换：RTO 高；
2. 无合规校验：违规风险。

## Consequences

优点：自动切换 + 合规安全。

缺点：选主需仲裁/健康语义。

风险：脑裂由仲裁与 term 兜底。

## Implementation

代码影响范围：`replication/active/` + `compliance/` + 测试 +
`docs/{multi-region/global-leader-selection,compliance/data-sovereignty}.md`。
