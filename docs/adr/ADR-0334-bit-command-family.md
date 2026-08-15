# ADR-0334: BIT Command Family

## Status

Accepted

## Context

Redis 兼容面（P2 功能深度）缺少位操作命令。项目命令层已具备
STRING 类型裸字节存储与 AtomicStringOps 原子更新（ADR-0269），
可直接在其上实现 SETBIT/GETBIT/BITCOUNT/BITPOS/BITOP。

## Decision

- BIT 值按 Redis 语义存为裸字节字符串（0/1 位按大端字节序）；
- SETBIT/GETBIT 以字节偏移 = offset/8、位偏移 = 7-(offset%8)；
  SETBIT 超出当前长度以零字节扩展（上限保护防 OOM）；
- BITCOUNT/BITPOS 支持 `[start end [BYTE|BIT]]`，负索引从末尾
  计数；BITPOS 缺失键语义：bit=0 返回 0、bit=1 返回 -1；显式范围
  未命中返回 -1；
- BITOP AND/OR/XOR/NOT：缺失源按零串处理，结果长度为最长源；
  NOT 仅允许单源；结果写入目标键（含全零结果，与 Redis 一致）；
- WRONGTYPE：非 STRING 类型键拒绝。

## Alternatives

1. 引入独立 BitSet 类型：破坏 Redis "字符串即位图" 语义；
2. 仅实现 GETBIT/SETBIT：覆盖不足。

## Consequences

优点：Redis 语义对齐、SETRANGE/GETRANGE 等既有命令自然兼容。

缺点：超大 offset（≥2^32）不支持（内存上限保护，返回错误）。

风险：BITPOS 边界语义（范围 vs 无范围）易错——测试矩阵覆盖。

## Implementation

`command/BitCommand.java` + `command/BitCommandFamilyTest`；
注册 setbit/getbit/bitcount/bitpos/bitop。
