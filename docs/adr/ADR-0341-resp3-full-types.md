# ADR-0341: RESP3 Full Types

## Status

Accepted

## Context

RESP3 编码器已支持 Map/Set/Push/Double/BigNumber（ADR-0283），
HGETALL/SMEMBERS 已版本感知；但 HELLO/CONFIG GET/集合运算仍恒返回
RESP2 数组，RESP3 null 仍编码为 `$-1`/`*-1` 而非 `_`。P2 剩余项
要求 RESP3 完整类型命令级接线。

## Decision

- `RespEncoder.writeV3`：RespNull 编码为 `_\r\n`（RESP3 null），
  RESP2 保持 `$-1`/`*-1`；
- 版本感知输出（读取 ConnectionContext 连接态，与 HGETALL/SMEMBERS
  同模式）：
  - HELLO 3 → RespMap（server/version/proto/mode 键值对）；
    HELLO 2 → 平铺数组；
  - CONFIG GET → RESP3 RespMap / RESP2 平铺数组；
  - SMEMBERS/SINTER/SUNION/SDIFF/SPOP count → RESP3 RespSet /
    RESP2 数组（SRANDMEMBER 保持数组：允许重复元素，与 Redis 一致）；
- 既有 HGETALL/SMEMBERS 的版本感知作为回归面固化；编码器/命令
  层不新增版本参数（连接态 ThreadLocal 模式不变）。

## Alternatives

1. Command 接口增加版本参数：全部命令签名变更，侵入大；
2. 命令层统一包装 RESP3 类型转换器：与 Redis 逐命令 schema 不符。

## Consequences

优点：客户端协议完整（Map/Set/null 原生表达），RESP2 回退不变。

缺点：逐命令版本感知（非全自动）；PubSub RESP3 push 消息仍未命令级
接线（文档登记）。

风险：wire 格式变化需回归——新增字节级 wire 测试。

## Implementation

`protocol/RespEncoder.java`（null）、`command/HelloCommand.java`、
`command/ConfigCommand.java`、`command/SetFamilyCommand.java` +
`Resp3FullTypeWireTest`。
