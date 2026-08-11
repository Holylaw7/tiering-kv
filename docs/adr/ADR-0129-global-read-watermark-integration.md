# ADR-0129: Global Read Watermark Integration

## Status

Accepted

## Context

Phase 29 GlobalReadRouter 使用注入水位。需要与真实复制管道/双向 CRDT
已应用水位联动，并输出陈旧度 SLA。

## Decision

1. 水位来源：复制管道（Phase 27）/ 双向 CRDT（Phase 28）已应用 seq；
2. `GlobalReadRouter` 接入水位提供者（Supplier<Long>）；
3. 陈旧度分位报告（p50/p95/p99）；
4. 水位滞后触发告警（Phase 29 AlertManager 联动）。

## Alternatives

1. 静态水位：无法反映真实滞后；
2. 无 SLA：读一致性不可量化。

## Consequences

优点：读一致性可观测可告警。

缺点：水位采样频率影响精度。

风险：水位与读路径竞态需容忍。

## Implementation

代码影响范围：`dr/GlobalReadRouter` + 测试 +
`docs/dr/global-read-watermark.md`。
