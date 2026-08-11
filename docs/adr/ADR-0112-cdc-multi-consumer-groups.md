# ADR-0112: CDC Multi-Consumer Groups

## Status

Accepted

## Context

Phase 26 CDC 为单消费者 checkpoint。多下游（数仓/搜索/复制）需要独立
进度与隔离故障。

## Decision

新增 `cdc/`：

1. `ConsumerGroup`：groupId + 独立 CDCCheckpoint；
2. `CDCConsumerRegistry`：注册/列表/删除组，逐组消费同一 CdcLog；
3. exactly-once 语义按组保持（ADR-0105 延续）；
4. 单事件多组投递，组间进度互不影响。

## Alternatives

1. 每下游复制日志：存储放大；
2. 共享 checkpoint：一个消费者故障拖垮全部。

## Consequences

优点：多下游独立推进；故障隔离。

缺点：多组消费增加读放大（内存可缓存段）。

风险：删除组需确认 checkpoint 语义，避免重复投递误解。

## Implementation

代码影响范围：`cdc/ConsumerGroup` + `CDCConsumerRegistry` + 测试 +
`docs/cdc/fanout-design.md`。
