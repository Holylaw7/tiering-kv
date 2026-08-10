# WAL 基准报告（WAL Report）

Phase 4 · 2026-08-10

环境：本机 Windows，JDK 21（编译目标 17），SSD，`FsyncPolicy.EVERY_SEC`
（近似 group commit，≤1s 批量 force），JVM `-Xmx1g`。

## WAL append throughput（buffered mode）

> ⚠️ 口径：本报告吞吐/延迟为**缓冲写入模式**（EVERY_SEC：记录写入文件缓冲，
> 非逐条 fsync），**不等同 durable write throughput**；ALWAYS（每次 append +
> fsync）模式的吞吐会显著下降，属正常现象，Phase 9 补测对比。

| 记录数 | P50 (ms) | P95 (ms) | P99 (ms) | 吞吐 (ops/s) |
| --- | --- | --- | --- | --- |
| 100K | 0.0017 | 0.0032 | 0.0068 | 460,689 |
| 1M | 0.0003 | 0.0008 | 0.0015 | 1,920,037 |

目标：append P99 < 1ms（buffered mode）✅（余量 >100×）。

## 恢复耗时

| 记录数 | 恢复时间 | 重放数 | 恢复后 size |
| --- | --- | --- | --- |
| 100K | 92.3ms | 100,000 | 100,000 |
| 1M | 568.5ms | 1,000,000 | 1,000,000 |

目标：1M 记录恢复 < 秒级 ✅（0.57s）。

说明：EVERY_SEC 存在 ≤1s 丢失窗口；ALWAYS 策略用于强一致场景（延迟更高，
未在本报告测量）；正式 JMH 基准在 Phase 9 建立。
