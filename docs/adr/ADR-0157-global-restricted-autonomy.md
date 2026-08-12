# ADR-0157: Global Restricted Autonomy

## Status

Accepted

## Context

Phase 34 的容量/流量护栏相互独立，缺少跨地域全局编排：容量建议、
流量配额调整与重分片计划未联动，无法形成"预测 → 执行 → 验证 → 回滚"
的全球受限自治闭环。

## Decision

1. `capacity/ai/GlobalAutonomyOrchestrator`：跨地域容量建议 + 流量配额
   调整 + 重分片计划联动；
2. `gateway/GlobalTrafficAutonomy`：多地域配额联合调整（限幅 + 回滚）；
3. 策略围栏：日预算 / 地域上限 / 熔断 / 回滚；
4. 验收：全局护栏矩阵（越界拒绝/回滚）、跨地域联动幂等、失败登记。

## Alternatives

1. 各控制器独立运行：全局联动缺失，资源竞争；
2. 全自动无围栏：容量与成本风险不可控。

## Consequences

优点：全球受限自治，变更可审计可回滚。

缺点：编排需要策略配置。

风险：预测偏差由围栏与熔断兜底。

## Implementation

代码影响范围：`capacity/ai/` + `gateway/` + 测试 +
`docs/capacity/global-autonomy.md`。
