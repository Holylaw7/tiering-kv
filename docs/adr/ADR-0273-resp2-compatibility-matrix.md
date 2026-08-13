# ADR-0273: RESP2 Compatibility Matrix

## Status

Accepted

## Context

新增命令大量使用整数/nil/空串/错误/批量数组回复，需要以 Redis 7.x
语义为基准建立协议兼容矩阵，避免回复形态漂移。

## Decision

采用兼容矩阵测试 + 文档：

- 整数回复：INCR/DEL/EXISTS/DBSIZE/STRLEN/TTL；
- nil vs 空串：GET 缺失 = nil，STRLEN 缺失 = 0，MGET 缺失元素 =
  nil；
- 错误消息：`ERR value is not an integer or out of range`、
  `WRONGTYPE`、`unknown command`、wrong arity，大小写对齐；
- pipeline 多命令保序；编码往返一致；
- 差异项在 docs/protocol/resp2-compatibility-matrix.md 如实登记。

## Alternatives

1. 按自己习惯设计回复：客户端不兼容；
2. 只测命令不测编码：错误形态漏网；
3. 无文档矩阵：差异不可审计。

## Consequences

优点：客户端兼容可测、错误形态稳定、差异可审计。

缺点：Redis 语义细节（如 TTL 取整）需要逐项对齐。

风险：Redis 版本间行为差异需固定参照（Redis 7.x）。

## Implementation

`src/test/java/io/tieringkv/protocol/ProtocolCompatibilityTest.java` +
`docs/protocol/resp2-compatibility-matrix.md`。
