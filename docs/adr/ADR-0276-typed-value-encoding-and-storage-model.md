# ADR-0276: Typed Value Encoding & Storage Model

## Status

Accepted

## Context

存储只有 string。hash/list/set/zset 需要类型化编码且不能破坏 WAL/RPC
冻结格式；TTL 必须键级生效；GET/TYPE 需要类型判定与 WRONGTYPE。

## Decision

采用 value 字节携带类型标签的 additive 编码：

- 字符串保持裸字节（向后兼容）；类型化值前缀 `TK` 魔数 + 类型字节 +
  payload；
- `TypedValueCodec` 判定类型；`HashCodec/ListCodec/SetCodec/ZSetCodec`
  紧凑二进制编解码；
- MemTable 值整体存字节，段锁内 read-modify-write；
- `AtomicStringOps.update(key, transform)`：段锁内读旧值 → 转换 →
  写新值（保留 TTL），WAL 装饰器同步委托；
- WAL 以 value 字节整体落盘（格式不变），恢复按标签解码。

## Alternatives

1. 修改 WAL/RPC 格式增加 type 字段：破坏冻结格式；
2. 每字段独立键（hash 字段拆键）：破坏键级 TTL 与事务语义；
3. 命令层无锁 get+put：并发丢更新。

## Consequences

优点：格式冻结、键级 TTL、单键原子、恢复兼容。

缺点：整值重写 O(size)，大数据结构性能受限（文档登记）。

风险：魔数冲突需类型判定测试覆盖。

## Implementation

`io.tieringkv.storage.types.*`、`AtomicStringOps.update`、
MemTable/WALStorageEngine 实现 +
`src/test/java/io/tieringkv/storage/types/TypedValueCodecTest.java`。
