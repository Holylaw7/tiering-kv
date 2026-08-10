# ADR-0018: SSTable File Format Design

## Status

Accepted

## Context

冷层文件需要稳定、可校验、可随机读的格式（ADR-0005 统一二进制约定）。

## Decision

文件布局（自底向上偏移）：

```text
[Data Blocks ...]      # 有序键块，默认目标 4KB
[Index Block]          # firstKey / offset / size
[Bloom Filter Block]   # bits-per-key=10，FPR<1%
[Footer 53B]
```

1. **Data Block**：`[MAGIC "TKDB" 4B][VERSION 1B][ENTRY_COUNT 4B][CRC32C 8B]`
   + 条目；条目 = `[TYPE 1B（PUT/TOMBSTONE）][KEY_LEN 4B][VALUE_LEN 4B]
   [CREATE_TS 8B][UPDATE_TS 8B][EXPIRE_TS 8B（绝对，-1 永不过期）][VERSION 8B]
   [KEY][VALUE]`；
2. **Index Block**：`firstKey / offset(8B) / size(4B)` 列表，块内二分定位；
3. **Bloom Block**：`[BIT_COUNT 4B][HASH_COUNT 4B][BITS]`（双哈希：FNV-1a +
   splitmix64）；
4. **Footer**：`[MAGIC "TKSF" 4B][VERSION 1B][INDEX_OFFSET 8B][INDEX_SIZE 8B]
   [BLOOM_OFFSET 8B][BLOOM_SIZE 8B][CRC32C 8B]`；
5. 所有整数大端序；版本不识别时拒绝打开；每块 CRC32C 校验（防半写/位翻转）；
6. 表内键唯一（写入方保证），跨表以"表序 + 全量合并"解决重复（ADR-0019）。

## Alternatives

1. 单文件大索引：实现简单但读放大（需加载全量索引）；
2. 无 Bloom：冷读全部走索引/磁盘，读放大高；
3. Protobuf 序列化：依赖与格式演进成本（ADR-0005 已否决）。

## Consequences

**优点：** 随机读路径可控（Bloom 过滤 + 块级定位）；CRC 保证损坏可检测。
**缺点：** 自研格式需完整测试（损坏/截断/版本）。
**风险：** 块大小与索引间隔参数需调优 → 配置化（blockTargetBytes 默认 4KB）。

## Implementation

- `SSTableWriter` / `SSTableReader` / `Block` / `BlockIndex` / `BloomFilter` /
  `SSTableMeta`；与 WAL 记录共用 CRC32C 工具。
