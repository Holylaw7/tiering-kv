# ADR-0142: Cross-Region Validation & v1.5 Freeze

## Status

Accepted

## Context

v1.4 后生产接线能力进入冻结窗口，需要跨地域真实基准与 v1.5 标签。

## Decision

1. `release.yml` 扩展 v1.5.0 标签；
2. 跨地域基准（Linux Runner）：SQL 2PC 延迟、Active-Active 冲突率/
   收敛时间、自动选主 RTO、读陈旧度（如实记录）；
3. 旧客户端兼容矩阵继续执行（ADR-0103）。

## Alternatives

1. 不冻结：接口漂移；
2. 仅本地基准：无法证明跨地域行为。

## Consequences

优点：v1.5 契约稳定、跨地域数据可发布。

缺点：跨地域执行依赖 CI/裸机。

风险：网络成本不可控，需超时与重试。

## Implementation

代码影响范围：`release.yml` + 基准测试 + 报告文档。
