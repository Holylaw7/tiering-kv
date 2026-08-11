# ADR-0086: Cross Machine Chaos Validation

## Status

Accepted

## Context

Phase 20 的跨机验证因容器内 Maven 网络受限未执行（TD-040/TD-043）；
Phase 21 具备网络化事务协议后，需要在真实 Linux + Docker 环境验证
分布式事务一致性。

## Decision

3 节点 Docker Compose 拓扑：

- node1：Gateway + Region Leader；
- node2：Region Replica；
- node3：Metadata + Replica；

故障注入：

- 网络：tc netem delay 100ms / loss 5% / duplicate / partition；
- 存储：disk slow（延迟写）、disk full（ENOSPC 模拟）；
- 进程：kill -9 / restart；

验证：transaction consistency、MVCC snapshot、recovery、
leader election；输出 `docs/testing/phase21-real-chaos-report.md`。

若执行环境再次受限：如实登记 TD，禁止伪造；以本地等价混沌兜底
（进程内 RPC 故障注入 + 网络 2PC 重试测试）。

## Alternatives

1. 仅本地混沌：缺少真实网卡/内核路径证据。
2. 云集群：依赖外部资源。

## Consequences

优点：

- 分布式事务在真实网络故障下获得证据。

缺点：

- 环境搭建成本高，Windows 开发环境受限。

风险：

- 中；TD 登记 + 本地等价验证兜底。

## Implementation

- `deploy/`：compose + netem 脚本；
- `docs/testing/phase21-real-chaos-report.md`；
- 本地等价：TxnNetworkFailureTest（RPC 超时/丢包/重试/leader 变更）。
