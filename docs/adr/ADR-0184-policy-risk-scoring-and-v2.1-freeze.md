# ADR-0184: Policy Risk Scoring & v2.1 Freeze

## Status

Accepted

## Context

网络策略已可审计，但缺少风险量化与可视化；v2.0 后需要冻结 v2.1 契约。

## Decision

1. `security/network/PolicyRiskScorer`：规则驱动风险评分（跨域白名单
   数量、deny 缺失、私有域暴露）；
2. `security/network/RiskDashboard`：按租户/策略聚合的风险视图；
3. 与 PolicyAuditView 联动；
4. `release.yml` 扩展 v2.1.0 标签 + Phase38BenchmarkTest 接入；
5. 验收：评分矩阵 + 聚合正确性。

## Alternatives

1. 无评分：风险不可量化；
2. 黑盒 ML 评分：不可解释。

## Consequences

优点：风险可量化可解释。

缺点：规则需维护。

风险：规则偏差由参数化测试兜底。

## Implementation

代码影响范围：`security/network/` + `release.yml` + 测试 +
`docs/{security/policy-risk-scoring,benchmark/phase38-production-report,release/v2.1.0-release-notes}.md`。
