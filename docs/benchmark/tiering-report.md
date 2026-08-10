# 自动调度基准报告（Tiering Report）

Phase 6 · 2026-08-10

环境：本机 Windows，JDK 21（编译目标 17），SSD；TieringController 默认配置
（flush 1 worker + migration 2 workers，水位 70/85/95），`-Xmx1g`。

## 自动 Flush

| 条目数 | 耗时 | 吞吐 |
| --- | --- | --- |
| 100K | 116.6ms | 857K entries/s |
| 200K | 237.0ms | 844K entries/s |

## 异步迁移（100K / 1M 条目，2 workers）

| 条目数 | 吞吐 | 成功率 | 端到端 P99（含队列等待） |
| --- | --- | --- | --- |
| 100K | 297K ops/s | 100% | 69.5ms |
| 1M | 308K ops/s | 100% | 1397.9ms |

目标：>50K entries/s ✅（余量 >6×）。P99 为"提交→完成"端到端延迟，
包含提交循环快于消费时的队列堆积（尾延迟）；单任务执行延迟远低于此，
生产可用背压（CRITICAL 限写）控制队列深度。

## 内存压力稳定性

- 20,000 次连续写入（2MB 配额）：81K puts/s，峰值 used=350KB << 2MB ✅；
- 自动 Flush + 异步迁移 + 背压协同下内存从未超配额，无 BackpressureException；
- 客户线程未执行磁盘 IO（flush/migration 均在后台 worker）。

说明：正式 JMH 基准在 Phase 9 建立；"客户端延迟影响 <5%"目标需与网络链路
合并测量（Phase 9）。
