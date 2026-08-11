# ADR-0109: Geo Distributed Transaction

## Status

Accepted

## Context

跨地域场景需要事务的 participant 分布在不同地域。v1 的 2PC 语义
（Percolator + 元数据 Raft 决策，ADR-0073/0087/0095）保持不变，仅
participant 传输远程化。

## Decision

新增 `transaction/geo/`：

1. `GeoRpcTransport`：地域间 prewrite/commit/rollback 调用抽象；
2. `GeoRegionTxnClient`：远程 participant 客户端（幂等重试）；
3. `GeoDecisionLog`：地域决策持久化（txnId + decision + CRC），
   区域故障后重放恢复；
4. 决策仍经元数据 Raft（Raft-first 不变），不引入第二决策源。

## Alternatives

1. 全局 2PC 状态机重写：破坏 v1 冻结语义；
2. 仅异步复制：无法保证跨地域原子性。

## Consequences

优点：跨地域提交无丢失无重复；恢复路径可验证。

缺点：跨地域 RTT 直接进入提交延迟。

风险：区域故障期间的未决事务依赖决策日志恢复。

## Implementation

代码影响范围：`transaction/geo/` + 测试 +
`docs/multi-region/geo-transaction.md`。
