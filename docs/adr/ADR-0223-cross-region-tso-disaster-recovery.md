# ADR-0223: Cross-Region TSO Disaster Recovery

## Status

Accepted

## Context

Phase 43 的 `TsoService` 支持批量分配 + 单调 + 恢复不回退，但为单点
部署。生产系统需要主备 TSO：主节点故障时备用节点接管，且时间戳必须
保持单调、切换不回退。

## Decision

新增 `TsoDisasterRecovery`：

- 主备双实例：主分配水位定期同步到备（水位快照）；
- 切换：主故障 → 备以已同步水位 + 1 继续分配（单调不回退）；
- 恢复：新主恢复旧主水位，分配游标越过水位（复用 TsoService.restore）；
- 与 resolved-ts / 事务协调器联动：水位推进驱动读水位。

## Alternatives

1. 多主并发分配：需要租约/仲裁，复杂度高；
2. 无备份：单点故障导致全局时间戳不可用；
3. 时钟漂移补偿：不解决分配单调性。

## Consequences

优点：RTO 短（切换即用）；单调性由水位继承保证。

缺点：故障期间已分配未持久化水位可能回退（由 restore 语义兜底）。

风险：主备水位同步延迟 → 切换后时间戳跳跃，可接受（只增不减）。

## Implementation

`transaction/tso/TsoDisasterRecovery` +
`src/test/java/io/tieringkv/transaction/tso/TsoDisasterRecoveryTest`、
`docs/transaction/tso-disaster-recovery.md`。
