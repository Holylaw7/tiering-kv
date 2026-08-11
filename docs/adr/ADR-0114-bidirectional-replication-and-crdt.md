# ADR-0114: Bidirectional Replication and CRDT

## Status

Accepted

## Context

Phase 27 复制为单向（Region A → B，主地域优先 + 冲突标记）。多主场景
需要双向写入合并且最终收敛，不能分裂。

## Decision

新增 `replication/crdt/`：

1. CRDT 原语：LwwRegister / GCounter / GSet / ORSet + 合并器；
2. `BidirectionalPipeline`：双向投递 + VersionVector 因果检测与环回
   抑制（已见事件不重复应用）；
3. 默认冲突策略 LWW（时间戳 + 节点优先级），可配置 CRDT 类型；
4. 单向路径（Phase 27 ReplicationPipeline）保持零回退。

## Alternatives

1. 全局锁/单主：牺牲多主可用性；
2. 无 CRDT 的 last-write-wins：跨节点时钟偏差下不可收敛。

## Consequences

优点：多主写入最终收敛，无环回风暴。

缺点：CRDT 语义与常规 KV 覆盖语义不同，需文档化。

风险：时钟偏差影响 LWW 判定，需节点优先级兜底。

## Implementation

代码影响范围：`replication/crdt/` + `replication/BidirectionalPipeline` +
测试 + `docs/multi-region/{bidirectional-replication,crdt-design}.md`。
