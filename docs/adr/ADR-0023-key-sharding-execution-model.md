# ADR-0023: Key Sharded Execution Model

## Status

Accepted

## Context

命令层当前在 Netty 事件循环内同步执行：单连接串行、不同连接抢占事件循环、
多核利用不足。候选模型：

- **Global Executor**：共享线程池执行所有命令——简单，但同键无顺序保证，
  且全局队列竞争；
- **Key Sharding**：`hash(key) % N` 路由到分片，每分片单 worker——同键 FIFO
  有序、异键并行（ADR-0003 既定方向）；
- **Actor Model**：每键一 actor——顺序天然，但键数量大时对象与调度开销高；
- **Lock Based**：事件循环 + 细粒度锁——无跨核并行收益。

## Decision

采用 **Key Sharding Execution Model**：

```text
Netty EventLoop → CommandEngine.executeAsync → KeyShardExecutor
    → ShardRouter → ShardQueue[N] → ShardWorker[N] → StorageEngine
```

1. 分片数默认 `min(16, CPU 核数)`，`fnv1a(key) % shardCount` 路由；
2. 同键命令进入同一分片队列，FIFO 执行（SET A → GET A → DEL A 有序）；
3. 不同键（不同分片）并行执行；
4. **响应保序**：每连接 ResponseSequencer 按请求序号释放响应，
  保证 RESP 协议"响应顺序 = 请求顺序"不被并行破坏；
5. 用户请求线程不执行 Flush / Migration / Compaction（后台 TierWorkerPool）；
6. 队列无界 + 指标观测；内存层面由 TieringController 背压兜底。

## Alternatives

1. Global Executor：同键顺序需额外锁/协调，被否决；
2. Actor Model：键多时开销高，与 MemTable 分段模型重叠；
3. Lock Based：无跨核收益，被否决。

## Consequences

**优点：** 多核利用、同键语义不变、接入点单一（CommandEngine）。
**缺点：** 响应保序需额外缓冲；热点分片倾斜（ADR-0025 缓解）。
**风险：** 分片队列堆积 → 指标 + 背压；顺序错误 → ResponseSequencer 测试覆盖。

## Implementation

- `io.tieringkv.execution`：KeyShardExecutor / ShardRouter / ShardQueue /
  ShardWorker / ExecutionContext / ConcurrencyMetrics；
- `io.tieringkv.network.connection.ResponseSequencer`；
- CommandEngine.executeAsync；Main 组装。
