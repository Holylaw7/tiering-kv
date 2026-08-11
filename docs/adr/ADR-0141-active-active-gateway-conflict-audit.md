# ADR-0141: Active-Active Gateway Conflict Audit

## Status

Accepted

## Context

Phase 31 Active-Active 冲突在管道内计数，网关无地域亲和与冲突审计。

## Decision

新增 `gateway/`：

1. `RegionAffinityRouter`：地域亲和写路由；
2. `ConflictAuditLog`：region/key/ts/winner 审计；
3. 网关执行读水位（Phase 30）校验。

## Alternatives

1. 随机路由：延迟不可控；
2. 无审计：冲突不可追溯。

## Consequences

优点：写延迟可控、冲突可审计。

缺点：审计日志增长需保留策略。

风险：亲和路由与 Active-Active 需一致。

## Implementation

代码影响范围：`gateway/` + 测试 +
`docs/multi-region/gateway-conflict-audit.md`。
