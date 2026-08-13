# ADR-0283: RESP3 Connection-Level Wiring

## Status

Accepted

## Context

Phase 52 提供 RESP3 类型与 HELLO 命令，但网络管道仍按 RESP2 编码，
HELLO 3 无法真正切换连接协议。

## Decision

采用连接级上下文 + 版本感知编码：

- `ConnectionContext`（连接线程持有）：RespVersion + Pub/Sub 订阅 +
  事务队列；
- CommandEngine 在命令执行期间设置 ThreadLocal 上下文；
- HELLO 命令直接切换当前连接版本；编码器按版本分发
  （RespEncoder.write(buf, value, version)）；
- HGETALL/SMEMBERS 按版本返回 Map/Set（RESP3）或数组（RESP2）；
- 批处理缓冲按连接版本编码，版本切换即时生效。

## Alternatives

1. 全局协议切换：破坏其他连接；
2. 每次命令带版本参数：侵入命令接口；
3. 只做类型不接线：HELLO 3 无实际效果。

## Consequences

优点：按连接隔离、RESP2 零影响、切换即时。

缺点：连接状态需要生命周期清理。

风险：异步分片执行时上下文传播需保证在事件循环内。

## Implementation

`io.tieringkv.session.ConnectionContext`、CommandEngine、
RespEncoder、HelloCommand、CommandHandler/ClusterCommandHandler +
`src/test/java/io/tieringkv/session/SessionContextTest.java`。
