# ADR-0287: MULTI/EXEC Transaction Queueing

## Status

Accepted

## Context

缺少 MULTI/EXEC/DISCARD/WATCH；命令级事务语义缺失。

## Decision

采用连接级事务队列：

- MULTI 开启 → 后续命令入队并返回 QUEUED；
- EXEC 顺序执行队列并返回结果数组（原子性依赖 applyBatch/同段，
  严格跨命令原子走 MVCC 事务路径）；
- DISCARD 清空；嵌套 MULTI 报错；
- WATCH 返回 OK（无版本守卫，文档登记为限制）。

## Alternatives

1. 无队列直接执行：MULTI 语义缺失；
2. 全量 MVCC 2PC：改动事务状态机，禁止；
3. WATCH 版本守卫：需存储版本 API，留后续。

## Consequences

优点：客户端语义完整、错误可测。

缺点：EXEC 非整体原子（文档明确）。

风险：队列状态泄漏需生命周期清理。

## Implementation

ConnectionContext 事务队列、MultiCommand/ExecCommand/DiscardCommand/
WatchCommand、CommandEngine 拦截 +
`src/test/java/io/tieringkv/command/MultiExecTransactionTest.java`。
