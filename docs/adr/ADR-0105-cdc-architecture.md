# ADR-0105: CDC Architecture

## Status

Accepted

## Context

企业场景需要将数据变更（PUT / DELETE / TXN_COMMIT / REGION_MOVE）
流式同步到下游（数仓/搜索/跨集群）。需要 exactly-once 消费语义与
崩溃恢复。

## Decision

新增 `cdc/`：

1. `ChangeEvent`：序号化变更事件（seq / type / key / value / deleted /
   txnId / regionId / timestamp）；
2. `CDCProducer`：在 Raft apply / 存储写入路径旁路生成事件，追加到
   CDC 日志（文件分段 + CRC）；
3. `CDCConsumer`：从检查点后的 seq 开始消费，应用后推进
   `CDCCheckpoint`（持久化 seq）；
4. exactly-once：检查点持久化与事件应用按 seq 幂等，崩溃后从
   checkpoint+1 恢复。

## Alternatives

1. 下游全量重放：成本高、无边界；
2. 数据库触发器：与存储引擎耦合，违反模块边界。

## Consequences

优点：CDC 独立模块，不触碰 Raft/MVCC 语义；消费可恢复可幂等。

缺点：CDC 日志额外存储；REGION_MOVE 事件需与迁移联动（Phase 27 深化）。

风险：checkpoint 与消费之间崩溃可能重复投递，靠幂等消费兜底。

## Implementation

代码影响范围：

- `cdc/`（ChangeEvent / CDCProducer / CDCConsumer / CDCCheckpoint）；
- `CDCRecoveryTest` 与 `docs/cdc/cdc-design.md`。
