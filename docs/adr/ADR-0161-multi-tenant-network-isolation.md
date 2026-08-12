# ADR-0161: Multi-Tenant Network Isolation

## Status

Accepted

## Context

多租户已有 RBAC 与租户注册，但缺少网络级隔离边界：租户间默认可达，
无法满足 VPC/私有网络隔离需求。

## Decision

1. `security/network/NetworkIsolationDomain`：租户 → 网络域（VPC/子网/
   私有网络标志）；
2. `security/network/IsolationPolicy`：跨域通信默认拒绝 + 白名单；
3. 与 CredentialManager / TenantRegistry 联动；
4. 验收：隔离矩阵 + 白名单授权 + 越权拒绝。

## Alternatives

1. 仅 RBAC：无网络边界；
2. 手动网络配置：不可审计。

## Consequences

优点：租户级网络隔离，策略可审计。

缺点：白名单需显式配置。

风险：默认拒绝语义由隔离矩阵测试兜底。

## Implementation

代码影响范围：`security/network/` + 测试 +
`docs/security/network-isolation.md`。
