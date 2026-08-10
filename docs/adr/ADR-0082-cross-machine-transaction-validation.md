# ADR-0082: Cross Machine Transaction Validation

## Status

Accepted

## Context

Phase 15–18 的混沌验证在单机进程内模拟网络故障；事务 2PC 尚未在
真实跨机（Linux + Docker + tc netem）环境验证。分布式事务的正确性
必须覆盖真实网络延迟、丢包、分区与 kill -9。

## Decision

构建 3 节点 Docker 集群：

- node1/node2/node3，Region r1/r2/r3，Multi-Raft，Redis TCP Gateway；
- `tc netem` 注入 delay 100ms / loss 5% / partition；
- 验证：MVCC 事务一致性、2PC 恢复、leader transfer、migration；
- 输出 `docs/testing/phase20-chaos-report.md`。

若执行环境（Windows 无 Linux/Docker）不可用：如实登记 TD，执行
本地等价混沌（进程内延迟/丢包/分区/restart/kill）作为降级验证，
禁止伪造跨机报告。

## Alternatives

1. 仅单机混沌：无法验证真实网卡/内核路径。
2. 云上临时集群：环境依赖外部资源，不可控。
3. 跳过验证：违反生产化要求。

## Consequences

优点：

- 2PC/恢复/迁移在真实网络故障下获得证据。

缺点：

- 环境搭建与维护成本高；Windows 开发环境受限。

风险：

- 中；通过 TD 登记与本地等价验证兜底。

## Implementation

- `scripts/`：compose + netem 注入脚本；
- `docs/testing/phase20-chaos-report.md`；
- 本地等价混沌测试：TransactionChaosTest 扩展（进程内故障注入）。
