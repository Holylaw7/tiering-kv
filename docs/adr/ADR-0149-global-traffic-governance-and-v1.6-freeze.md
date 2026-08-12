# ADR-0149: Global Traffic Governance & v1.6 Freeze

## Status

Accepted

## Context

全球多活需要按地域配额与优先级治理流量（热点地域占满、其他地域饥饿）；
v1.5 后需冻结 v1.6 契约并发布跨地域真实基准。

## Decision

1. `gateway/RegionQuota`：地域写入配额；
2. `gateway/PriorityRouter`：优先级队列 + 降级；
3. `gateway/TrafficPolicy`：QPS/配额映射，与 RegionAffinityRouter
   联动；
4. `release.yml` 扩展 v1.6.0 标签；跨地域基准如实记录。

## Alternatives

1. 无配额：热点地域耗尽全局资源；
2. 全局令牌桶：无法表达地域策略。

## Consequences

优点：全球流量公平性 + 优先级降级可控。

缺点：配额配置需运维维护。

风险：基准跨地域执行依赖 Linux Runner，本地如实登记。

## Implementation

代码影响范围：`gateway/` + `release.yml` + 基准测试 +
`docs/{gateway/traffic-governance,benchmark/phase33-production-report,release/v1.6.0-release-notes}.md`。
