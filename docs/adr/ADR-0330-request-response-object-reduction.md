# ADR-0330: Request→Response Object Reduction

## Status

Accepted

## Context

TD-020/021：命令路径每个请求创建 Future/Lambda/Callback/Context 等
对象（3-5/request），百万级并发下 allocation/GC 显著。Phase 10 已做
Callback + ResponseBatcher，仍有可优化空间。

## Decision

- 审计命令路径对象分配（JFR allocation 采样基线）；
- 复用策略：无状态命令实例共享、可复用 RequestContext/ResponseBuffer
  （ThreadLocal/池化）、回调对象合并；
- 验收：单请求分配对象数下降 ≥30%（JFR 口径，TD-021）；
- 不改变协议/行为，纯内部对象优化。

## Alternatives

1. 维持现状：GC 压力可接受；
2. 全异步零分配框架：重构风险高。

## Consequences

优点：allocation 下降 → GC 频率/停顿下降。

缺点：池化对象生命周期管理复杂。

风险：复用错误导致状态泄漏——用 JFR 与并发回归覆盖。

## Implementation

命令路径对象审计 + 针对性复用；基准 phase64。
