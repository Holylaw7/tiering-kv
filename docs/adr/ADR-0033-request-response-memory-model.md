# ADR-0033: Request Response Memory Model

## Status

Accepted

## Context

当前每请求对象链：`RespCommand → CompletableFuture → whenComplete Lambda →
Outcome → TreeMap entry → RespValue → 独立 ByteBuf`，对象数多、allocation
率高（Phase 9 瓶颈 1）。

## Decision

削减 request→response 路径对象数：

```text
改造前：Future + Lambda + Callback + Context + 每响应 ByteBuf
改造后：Pending(seq, close) 单对象 + 回调式执行 + 每连接复用 ResponseBuffer
```

1. `CommandEngine.executeAsync(cmd, callback)`：不再创建 CompletableFuture，
   分片 worker 直接回调（异常经 callback 传递）；
2. `ResponseBuffer`：每连接复用单个 ByteBuf，编码累积后一次写出；
3. 静态复用：`+OK` / `+PONG` / nil 等常量 RespValue 实例；
4. 生命周期：全部状态存活于连接内（事件循环单线程），无跨线程共享；
5. 线程安全：Batcher/Buffer 仅由连接事件循环访问（worker 通过
   ctx.executor() 投递），无需额外同步。

## Alternatives

1. Arena 对象池复用 Future：生命周期复杂，收益不明确；
2. Direct Buffer 全路径：解码仍需堆对象，收益有限；
3. 保持现状：allocation 压力不解决。

## Consequences

**优点：** 每请求对象数下降（预计 3–5 个 → 1–2 个）；GC 压力降低。
**缺点：** 回调式 API 需显式错误处理；常量复用需注意不可变性。
**风险：** 缓冲泄漏 → 连接关闭强制 release；测试覆盖关闭路径。

## Implementation

- `CommandEngine` 回调重载；`CommandHandler` 接入；
- `ResponseBatcher` / `ResponseBuffer`；
- Benchmark Before/After（allocation/GC/QPS，phase10-performance-report）。
