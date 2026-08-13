# ADR-0281: RESP3 Protocol Evolution

## Status

Accepted

## Context

RESP2 无法表达 Map/Set/Double/Push；需要 additive 演进且 RESP2 客户端
零影响。

## Decision

RESP3 作为 additive 协议层：

- 新类型：RespMap（%）、RespSet（~）、RespDouble（,）、
  RespBigNumber（(）、RespPush（>）；
- `RespVersion`（RESP2/RESP3）+ `ConnectionProtocolState`（连接态）；
- HELLO 3 切换（返回 server 信息）；HELLO 2 回退；默认 RESP2；
- `RespEncoder.writeV3` 编码新类型；既有 write 保持 RESP2；
- 数据结构命令按协议版本返回 Map（HGETALL）/数组（RESP2）。

## Alternatives

1. 直接全量切 RESP3：破坏既有客户端；
2. 只用数组表达 Map：客户端类型提示缺失；
3. 不加协议版本状态：无法按连接切换。

## Consequences

优点：additive、RESP2 零影响、类型表达完整。

缺点：连接级状态需要网络层接线（Phase 53 全接线）。

风险：RESP3 编码细节需矩阵覆盖。

## Implementation

`io.tieringkv.protocol.{RespMap,RespSet,RespDouble,RespBigNumber,RespPush,RespVersion,ConnectionProtocolState}`、
`RespEncoder.writeV3`、`io.tieringkv.command.HelloCommand` +
`src/test/java/io/tieringkv/protocol/Resp3CompatibilityTest.java`。
