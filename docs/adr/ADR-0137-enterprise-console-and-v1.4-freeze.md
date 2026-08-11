# ADR-0137: Enterprise Console & v1.4 Freeze

## Status

Accepted

## Context

控制面需要 REST API 产品化（租户/集群/账单/告警查询 + 自服务），并进入
v1.4 冻结与全球多活基准窗口。

## Decision

1. `console/`：ConsoleServer（REST API）+ RBAC 接入（ADR-0110）+
   租户自服务（TenantClusterPlanner 联动）；
2. `release.yml` 扩展 v1.4.0 标签；
3. 全球多活基准（Linux Runner）：双地域写吞吐/冲突率/收敛时间/
   陈旧度（如实记录）。

## Alternatives

1. 无控制台：运维靠 CLI；
2. 不冻结：接口漂移。

## Consequences

优点：控制面可产品化、v1.4 契约稳定。

缺点：控制台为原型（完整 UI 待 Phase 32）。

风险：API 权限需严格矩阵。

## Implementation

代码影响范围：`console/` + `release.yml` + 基准测试 +
`docs/{console/console-api,benchmark/phase31-production-report}.md`。
