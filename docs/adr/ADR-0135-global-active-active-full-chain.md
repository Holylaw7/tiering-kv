# ADR-0135: Global Active-Active Full Chain

## Status

Accepted

## Context

Phase 28 双向复制为单地域对。全球多活需要多地域同时读写、冲突实时
合并、环回抑制与冲突审计。

## Decision

新增 `replication/active/`：

1. `ActiveActivePipeline`：双向投递 + 环回抑制 + 实时 CRDT 合并；
2. `ConflictMetrics`：冲突率/收敛时间；
3. 网关：地域亲和写路由 + 冲突事件审计；
4. 全球读水位（Phase 30）联动。

## Alternatives

1. 单主 + 异步复制：写可用性受限；
2. 无冲突处理：数据分裂。

## Consequences

优点：多地域写可用、冲突可收敛可审计。

缺点：CRDT 语义需文档化。

风险：环回风暴需版本向量抑制。

## Implementation

代码影响范围：`replication/active/` + 测试 +
`docs/multi-region/active-active.md`。
