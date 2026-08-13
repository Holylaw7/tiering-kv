# ADR-0293: Blocking Commands

## Status

Accepted

## Context

BLPOP/BRPOP 语义缺失；阻塞等待必须不阻塞事件循环。

## Decision

采用条件通知 + 超时等待：

- `BlockingListNotifier`：push 通知等待者；
- BLPOP/BRPOP key... timeout：先尝试即时弹出，空则按 timeout
  等待（0 = 无限）；
- 等待在命令执行线程（事件循环外），事件循环不阻塞；
- 返回 [key, value] 或 nil（超时）。

## Alternatives

1. 自旋轮询：CPU 浪费；
2. 事件循环内 sleep：阻塞吞吐；
3. 无阻塞语义：客户端不兼容。

## Consequences

优点：语义完整、事件循环安全。

缺点：等待线程占用 worker 资源。

风险：通知丢失需超时兜底重试。

## Implementation

`io.tieringkv.operations.BlockingListNotifier`、ListCommand 扩展 +
`src/test/java/io/tieringkv/command/BlockingCommandTest.java`。
