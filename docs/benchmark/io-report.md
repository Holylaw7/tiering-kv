# IO 优化基准报告（IO Report）

Phase 8 · 2026-08-10

环境：本机 Windows，JDK 21（编译目标 17），SSD，块 4KB，OS page cache 热
（文件刚写入即读）；指标为冷层直读（不含网络/RESP/WAL）。

## mmap vs FileChannel（随机 / 顺序 GET）

| 场景 | FileChannel P50/P99 | mmap P50/P99 | mmap 吞吐提升 |
| --- | --- | --- | --- |
| 随机 100K | 0.013 / 0.092ms | 0.004 / 0.040ms | 52K → 108K ops/s（2.1×） |
| 随机 1M | 0.007 / 0.021ms | 0.004 / 0.012ms | 117K → 212K ops/s（1.8×） |
| 顺序 100K | 0.006 / 0.018ms | 0.003 / 0.007ms | 151K → 314K ops/s（2.1×） |
| 顺序 1M | 0.006 / 0.013ms | 0.003 / 0.007ms | 170K → 314K ops/s（1.8×） |

目标：随机读 P99 < 5ms ✅（余量 >100×）。mmap 零拷贝路径在随机与顺序读上
均优于 FileChannel。

## Block Cache（200K 键，20K 样本）

| 阶段 | P99 | 说明 |
| --- | --- | --- |
| 冷读 | 0.038ms | 首次 mmap 读 + 回填 |
| 热读 | 0.023ms | 缓存命中（命中率 94.79%） |
| 混合 | 0.017ms | 50% 热点 + 50% 冷键 |

目标：Warm P99 < 1ms ✅；命中率 94.8%（容量 4096 块）。

## 内存概况（100K 键 + 50K 随机读）

- MemoryPool：allocated ≈ 25MB（缓存块池化），reuse=0（单次遍历无淘汰），
  peak ≈ 25MB；GC 计数 +3（小规模样本）。
- 说明：缓存体为 off-heap DirectByteBuffer（不占堆）；heap 分配减少主要来自
  块读取零拷贝路径。10M 键与 JFR 深度剖析需手动运行。

说明：page cache 热口径；cold-cache（drop cache）基准在 Phase 9（TD-009）。
