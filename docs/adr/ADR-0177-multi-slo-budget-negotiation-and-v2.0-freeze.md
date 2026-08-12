# ADR-0177: Multi-SLO Budget Negotiation & v2.0 Freeze

## Status

Accepted

## Context

Phase 36 的 SLO 预算为单 SLO 规划，多个 SLO 并存时无联合优化；
v1.9 后需要冻结 v2.0 GA 契约。

## Decision

1. `operations/slo/MultiSloNegotiator`：多 SLO（达成率 × 权重）→
   联合预算缺口 → 容量建议；
2. 权重可配置，最差 SLO 优先；
3. `release.yml` 扩展 v2.0.0 标签 + Phase37BenchmarkTest 接入；
4. 验收：联合矩阵 + 最差优先 + 权重影响。

## Alternatives

1. 单 SLO 规划：联合缺口遗漏；
2. 不冻结：GA 契约漂移。

## Consequences

优点：多 SLO 联合优化，GA 契约稳定。

缺点：需要多 SLO 输入。

风险：权重偏差由最差优先兜底。

## Implementation

代码影响范围：`operations/slo/` + `release.yml` + 测试 +
`docs/{operations/multi-slo-negotiation,benchmark/phase37-production-report,release/v2.0.0-release-notes}.md`。
