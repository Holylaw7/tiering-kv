# ADR-0085: Online MVCC Compression

## Status

Accepted

## Context

MVCC 索引目前只支持快照重建与批量 GC；缺少“在线压缩”：
把可回收的旧版本合并掉并生成新的 MVCC 索引文件，且不阻塞
read / write / transaction。

## Decision

新增 `MvccCompactor`：

- `compact()`：基于版本索引快照规划（保留每键最新 + commitTS >= SafePoint），
  通过 `deleteVersionGroups` 批量移除旧版本（索引不可变列表 + 短临界区，
  读/写/事务不阻塞）；
- 压缩完成后以临时文件 + 原子 move 写出新 MVCC 索引文件
  （`PersistentMvccIndex` 格式）；
- 后台定时执行（可配置 interval），支持手动触发；
- 指标：`mvcc_compaction_versions` / `mvcc_compaction_bytes`。

## Alternatives

1. 停写压缩：不可接受。
2. 复制-on-write 全量重建：内存翻倍。
3. 仅 GC 不落盘：缺少“新 MVCC 文件”产物。

## Consequences

优点：

- 在线回收版本并产出可恢复的压缩索引；
- 读路径无锁（不可变列表语义）。

缺点：

- 压缩与 GC 并行时需幂等（重复执行安全）。

风险：

- 低；由 MvccCompactionTest / ConcurrentReadWriteCompactionTest 验证。

## Implementation

- `src/main/java/io/tieringkv/mvcc/compaction/MvccCompactor.java`；
- 测试：MvccCompactionTest、ConcurrentReadWriteCompactionTest。
