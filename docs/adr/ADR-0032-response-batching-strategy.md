# ADR-0032: Response Batching Strategy

## Status

Accepted

## Context

Phase 9 定位瓶颈在协议/调度层：每响应一次 `writeAndFlush` 产生过多系统调用与
对象。候选：

- **A 立即发送**：低延迟，但系统调用多（现状）；
- **B 固定批量**：N 响应一次 flush，吞吐高但延迟固定增加；
- **C 自适应批量**：根据 pipeline 深度与未完成请求数动态调整。

## Decision

采用 **C 自适应批量（batch=64 + 排空即 flush）**：

```text
Response → ResponseSequencer（保序）→ ResponseBatcher（每连接）
    → ResponseBuffer（复用 ByteBuf 累积编码）
    → 批满 64 或本批未完成请求归零 → ctx.writeAndFlush(buf) 一次
```

1. `ResponseBuffer` 按连接复用（分配削减，ADR-0033）；
2. 低并发（无 pipeline）时未完成请求数快速归零 → 近似立即发送，延迟不劣化；
3. 高 pipeline 时聚合批量 flush，降低系统调用；
4. 顺序保证：仅当 ResponseSequencer 释放响应后才进入 Batcher
   （请求顺序 = 响应顺序不变）。

## Alternatives

1. 立即发送：保留为退化路径（batch=1 配置）；
2. 固定批量：延迟不可控，被否决；
3. 定时器批量：增加调度复杂度，Phase 10 不做。

## Consequences

**优点：** 吞吐提升（目标 pipeline64×500 >400K）、低并发延迟不受影响。
**缺点：** 高并发下响应累积等待（≤64 或本批排空）。
**风险：** 连接关闭时残留缓冲 → channelInactive/异常路径强制 flush。

## Implementation

- `io.tieringkv.network.response`：ResponseBatcher / ResponseBuffer；
- CommandHandler 接入；测试：ResponseBatcherTest / PipelineOrderingTest /
  ConcurrentResponseTest。
