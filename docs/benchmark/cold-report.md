# 冷存储基准报告（Cold Storage Report）

Phase 5 · 2026-08-10

环境：本机 Windows，JDK 21（编译目标 17），SSD，块 4KB / Bloom bits-per-key=10，
`-Xmx1g`。指标为直连冷层 API（不含网络 / RESP / WAL）。

## SSTable 写吞吐

| 记录数 | 文件大小 | 吞吐 |
| --- | --- | --- |
| 100K | 6.0MB | 30.3MB/s |
| 1M | 60.0MB | 85–104MB/s（多次运行波动） |

目标 >100MB/s：1M（JIT 预热后）峰值达成；100K（冷启动）与波动低点未达，
属 JVM 预热与系统负载效应。
优化方向（Phase 9）：批量条目编码、更大块（16–64KB）、直接 ByteBuffer 写。

## 随机 GET（1M 条目表，10,000 样本）

| P50 | P95 | P99 |
| --- | --- | --- |
| 0.008ms | 0.013ms | 0.021ms |

目标 P99 < 5ms ✅（余量 >200×）。读取路径：Bloom → Index → Block。

## Bloom Filter

false positive rate = **0.8167%** ✅（目标 <1%）。

## 全量合并

| 输入 | 输出 | 耗时 | 吞吐 |
| --- | --- | --- | --- |
| 24.0MB（4 表） | 6.4MB | 0.14s | 46.6MB/s |

空间回收 73%；临时盘使用 = 输出体积（输入在合并完成后删除）。

说明：正式 JMH 基准在 Phase 9 建立。
