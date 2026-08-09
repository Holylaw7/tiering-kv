# ADR-0005: Persistence Format

## Status

Accepted

## Context

WAL、Bitcask 与 LSM 需要稳定的磁盘文件格式。直接复用 Redis RDB/AOF 无法满足
自研存储引擎的分层需求；Protobuf 等通用序列化不适合作为存储层文件格式（版本演进
与随机访问控制不足）。

## Decision

采用**自定义二进制格式，统一约定 + 引擎各自实现**：

1. **统一头**：magic + 版本号，保证向前兼容与格式演进；
2. **记录格式**：`CRC32C | 时间戳 | key 长度 | value 长度 | 类型 | key | value`
   （TLV 风格），类型含 PUT / DELETE（tombstone）/ MERGE；
3. **Bitcask 文件**：追加写日志 + 索引项 `(fileId, offset, size)`；
4. **SSTable 文件**：有序键块 + 块索引 + 每文件 Bloom Filter，支持二分查找；
5. **WAL**：与记录格式统一，回放时忽略不完整尾记录（崩溃安全）。

格式细节在 `docs/design/{bitcask,lsm}-design.md` 中定义，实现前需更新本 ADR 或
新增子 ADR。

## Alternatives

1. Redis RDB/AOF 格式：生态兼容但粒度过粗，不支持引擎分层；
2. Protobuf/FlatBuffers：通用但存储层演进与随机访问控制弱；
3. 直接映射 RocksDB 格式：违反自研约束。

## Consequences

**优点：** 版本可控、随机访问友好、两引擎共享记录语义。
**缺点：** 格式自研需要完整测试（损坏、截断、版本迁移）。
**风险：** 格式缺陷导致数据不可读 → 写入时 CRC 校验 + 恢复测试覆盖。

## Implementation

- 模块：`storage/wal`、`storage/cold/bitcask`、`storage/cold/lsm`；
- Phase 4（Bitcask/WAL）与 Phase 5（SSTable）分别落地。
