# ADR-0256: Cross-Regulatory Federation Scale-out

## Status

Accepted

## Context

Phase 48 多组织联邦仲裁（ADR-0249）以 organization 为仲裁边界。Phase 49
需要扩展到跨监管域：同一组织可能跨多个监管域，监管域之间必须有独立
仲裁与回退 2PC 兜底，避免单域不合格污染全局一阶段。

## Decision

采用监管域边界发现 + 域级仲裁 + 回退 2PC：

- `CrossRegulatoryFederationArbitration`：cloud → regulatory domain
  映射自动发现；域内组织多数 → 域合格；域多数 → 跨域一阶段；
- 任一域不合格即回退 2PC（`fallback2Pc=true`），幂等结果缓存；
- 与 MultiOrgFederationArbitration / GlobalUnifiedOnePhaseArbitration /
  AsyncCommitCoordinator / ResolvedTimestampService 联动。

## Alternatives

1. 直接沿用组织级仲裁：无法感知监管域边界，合规风险不可控；
2. 全局多数一阶段：单个域不合格时仍可能提交，违反跨域一致性原则；
3. 一律回退 2PC：可用性低，无法体现跨域一阶段收益。

## Consequences

优点：域边界可审计、回退语义明确、幂等可重试。

缺点：仲裁层次增加，需要维护 cloud/domain 映射。

风险：映射漂移会导致仲裁口径变化，需版本化缓存与失效。

## Implementation

`src/main/java/io/tieringkv/transaction/async/CrossRegulatoryFederationArbitration.java`
+ `src/test/java/io/tieringkv/transaction/async/CrossRegulatoryFederationArbitrationTest.java`、
`docs/transaction/cross-regulatory-federation.md`。
