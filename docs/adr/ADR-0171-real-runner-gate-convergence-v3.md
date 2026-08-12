# ADR-0171: Real Runner Gate Convergence v3

## Status

Accepted

## Context

门禁收敛表 v2 已登记 TD-048/049、K8S-001、REL-001、BM-001/002 等
真实执行项；v2.0 GA 前需要收敛表 v3 精确追踪并执行可执行项。

## Decision

1. Linux Runner 执行：CI 容器 E2E、真实块设备磁盘混沌、kind 验证、
   release 流水线、跨机/跨地域基准；
2. 本环境可执行部分（JVM 级混沌/基准扩展）先行验证；
3. 门禁收敛表 v3：每项状态 / 阻塞原因 / 预期消除阶段；
4. 未执行项精确登记，禁止伪报完成。

## Alternatives

1. 继续等待 Runner：无进展记录；
2. 声称完成：违反工程诚信。

## Consequences

优点：v2.0 GA 门禁透明可追踪。

缺点：真实执行依赖 CI/裸机。

风险：网络成本不可控，需超时与重试。

## Implementation

代码影响范围：`release.yml` + 门禁测试 + 收敛表 v3 +
`docs/{deployment/gate-convergence-v3,review/phase37-multi-objective-autonomy-review}.md`。
