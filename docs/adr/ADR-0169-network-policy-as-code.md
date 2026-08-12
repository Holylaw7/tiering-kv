# ADR-0169: NetworkPolicy-as-Code

## Status

Accepted

## Context

Phase 35 的 IsolationPolicy 通过 Java API 维护白名单，缺少声明式
策略（YAML 风格 DSL）与校验，难以审计与版本化。

## Decision

1. `security/network/NetworkPolicyDsl`：声明式策略（allow/deny + 租户
   对）解析与校验；
2. `security/network/PolicyCompiler`：DSL → IsolationPolicy 白名单；
3. 非法策略拒绝，编译幂等；
4. 验收：DSL 解析矩阵 + 非法策略拒绝 + 编译幂等。

## Alternatives

1. 仅 Java API：不可版本化；
2. 引入外部框架：依赖重。

## Consequences

优点：策略即代码，可审计可版本化。

缺点：DSL 需维护。

风险：解析错误由非法输入拒绝兜底。

## Implementation

代码影响范围：`security/network/` + 测试 +
`docs/security/network-policy-as-code.md`。
