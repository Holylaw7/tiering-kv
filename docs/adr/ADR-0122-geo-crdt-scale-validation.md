# ADR-0122: Geo CRDT Scale Validation

## Status

Accepted

## Context

Phase 28 CRDT 收敛在小规模验证。规模（百万键 × 多节点并发写）与真实
时钟偏差下的收敛需要性质测试与校准。

## Decision

新增：

1. `replication/crdt/bench`：多节点 × 多键并发冲突模拟 + 收敛审计；
2. `HybridClockCalibrator`：节点间时钟偏差估计，LWW 决策可解释；
3. 性质测试：任意合并顺序最终一致（交换/结合/幂等）。

## Alternatives

1. 不校准时钟：LWW 在偏差下决策失真；
2. 仅小规模测试：无法证明规模收敛。

## Consequences

优点：规模收敛有证据、时钟偏差可量化。

缺点：模拟为进程内口径，跨机偏差待 CI。

风险：偏差超阈值需告警（Phase 29 Goal 7）。

## Implementation

代码影响范围：`replication/crdt/bench` + `HybridClockCalibrator` + 测试 +
`docs/multi-region/crdt-scale-report.md`。
