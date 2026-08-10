# 冷存储基准报告（Cold Storage Report）

Phase 5 · 2026-08-10

环境：本机 Windows，JDK 21（编译目标 17），SSD，块 4KB / Bloom bits-per-key=10，
`-Xmx1g`。指标为直连冷层 API（不含网络 / RESP / WAL）。

## SSTable 写吞吐

| 记录数 | 文件大小 | 吞吐 |
| --- | --- | --- |
| 100K | 6.0MB | 30.3MB/s |
| 1M | 60.0MB | 85–104MB/s（多次运行波动） |

口径（Phase 5 评审修正）：

- **Peak SSTable write throughput：104MB/s**（1M，JIT 预热后）；
- **Average steady-state：85MB/s**（多次运行）；冷启动（100K）约 30MB/s。

结论：峰值达成 >100MB/s 目标；平均稳态未达，优化方向（Phase 9）：批量条目
编码、更大块（16–64KB）、直接 ByteBuffer 写。
优化方向（Phase 9）：批量条目编码、更大块（16–64KB）、直接 ByteBuffer 写。

## 随机 GET（1M 条目表，10,000 样本）

| P50 | P95 | P99 |
| --- | --- | --- |
| 0.008ms | 0.013ms | 0.021ms |

目标 P99 < 5ms ✅（余量 >200×）。读取路径：Bloom → Index → Block。

> ⚠️ 缓存影响：本结果受 OS page cache 影响，代表"热缓存"随机读；
> 真磁盘冷读需 Phase 9 的 cold-cache benchmark（drop cache 后随机 1M 键）验证。

## Bloom Filter

false positive rate = **0.8167%** ✅（目标 <1%）。

## 全量合并

| 输入 | 输出 | 耗时 | 吞吐 |
| --- | --- | --- | --- |
| 24.0MB（4 表） | 6.4MB | 0.14s | 46.6MB/s |

空间回收 73%；临时盘使用 = 输出体积（输入在合并完成后删除）。

说明：正式 JMH 基准在 Phase 9 建立。

## 后续（Phase 9 计划）

- Cold-cache benchmark：清 OS 缓存后随机 1M 键的磁盘冷读 P50/P95/P99；
- SSTable 写吞吐优化（批量编码 / 更大块 / ByteBuffer）。
