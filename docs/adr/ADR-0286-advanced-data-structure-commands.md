# ADR-0286: Advanced Data Structure Commands

## Status

Accepted

## Context

基础命令族已齐，缺少常用高级命令（HSCAN/LINSERT/LMOVE/ZRANGEBYLEX）。

## Decision

补齐高级命令并复用段锁原子路径：

- HSCAN：字段快照游标 + MATCH/COUNT；
- LINSERT：BEFORE/AFTER 枢轴插入；
- LMOVE / RPOPLPUSH：源弹目标推（双键顺序执行，跨键原子性登记）；
- ZRANGEBYLEX / ZLEXCOUNT / ZREMRANGEBYLEX：字典序范围。

## Alternatives

1. 跳过高级命令：客户端生态不完整；
2. 命令层无锁实现：并发丢更新；
3. 双键原子新 API：存储内核改动过大。

## Consequences

优点：命令面完整、单键原子。

缺点：LMOVE 双键非整体原子（文档登记）。

风险：LEX 语法边界需矩阵覆盖。

## Implementation

HashCommand/ListCommand/ZSetCommand 扩展 +
`src/test/java/io/tieringkv/command/AdvancedDataStructureCommandTest.java`。
