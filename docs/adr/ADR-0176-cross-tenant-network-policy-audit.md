# ADR-0176: Cross-Tenant Network Policy Audit

## Status

Accepted

## Context

Phase 36 的策略即代码支持声明式编译，但策略变更无审计记录，跨租户
变更无法追踪与可视化。

## Decision

1. `security/network/NetworkPolicyAudit`：策略变更事件（DSL 来源 +
   时间 + 动作）记录；
2. `security/network/PolicyAuditView`：按租户/时间聚合的可视化数据源；
3. 与 PolicyCompiler 联动（编译时自动审计）；
4. 验收：审计矩阵 + 视图聚合正确性。

## Alternatives

1. 无审计：变更不可追踪；
2. 手工记录：不可靠。

## Consequences

优点：策略变更可审计可追溯。

缺点：审计存储需维护。

风险：审计遗漏由编译联动兜底。

## Implementation

代码影响范围：`security/network/` + 测试 +
`docs/security/network-policy-audit.md`。
