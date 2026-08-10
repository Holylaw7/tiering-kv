# ADR-0015: WAL Record Format

## Status

Accepted

## Context

WAL 需要稳定、可校验、可演进的磁盘格式。禁止 Java Object Serialization
（版本脆弱、体积大、安全风险）。候选：自定义二进制、文本、Protobuf。

## Decision

采用**自定义二进制格式**（ADR-0005 的统一约定）：

```text
[MAGIC 4B][VERSION 1B][TYPE 1B][TIMESTAMP 8B]
[KEY_LEN 4B][VALUE_LEN 4B][RECORD_VERSION 8B][TTL_MILLIS 8B]
[KEY nB][VALUE mB][CRC32C 8B]
```

- MAGIC = `0x544B5631`（"TKV1"），VERSION = 1；TYPE：1=PUT，2=DELETE；
- 所有整数大端序；DELETE 的 VALUE_LEN = 0；
- TTL_MILLIS 语义为**相对时长**（恢复时用 TIMESTAMP + TTL 计算绝对过期点，
  避免宕机期间过期键复活）；
- RECORD_VERSION 为 WAL 写入序号（审计用；恢复时由 MemTable 重新分配版本）；
- CRC32C 覆盖 magic 至 payload 末尾（checksum 字段之前），
  `ChecksumValidator` 负责计算与校验；
- 前向兼容：VERSION 不识别时拒绝打开；后续版本可扩展尾部字段。

## Alternatives

1. 文本格式：可读但解析慢、体积大、长度转义复杂；
2. Protobuf：生态成熟，但引入生成代码与依赖，且 ADR-0005 已定自定义二进制
   为存储层基调；
3. Java Serialization：明确禁止。

## Consequences

**优点：** 体积小（约 46B + key + value）、定长头便于随机定位、CRC32C 可检测
截断与位翻转。
**缺点：** 自研格式需完整测试（损坏/截断/版本）。
**风险：** 格式缺陷导致数据不可读 → 校验 + 恢复测试覆盖 + 版本字段兜底。

## Implementation

- `WALRecord`（encode/decode）、`ChecksumValidator`（CRC32C）、
  `WalCorruptionException`；
- 与 ADR-0005（Bitcask/SSTable）共用头部约定。
