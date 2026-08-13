# TiKV 跨机基准回归闭环（ADR-0253）

## 口径声明

本基线为**本地进程内**口径（JVM 单机、page cache 热），与 TiKV 公开
数据对比仅作数量级参考。**跨机待执行**：BM-001/002 部署脚本
（Gateway×3 / Metadata×3 / Storage×6）已就绪，由 Phase 49 Runner
执行；回归闭环（快照 + 趋势 + 告警 + 自动重跑）以登记为准，禁止伪报。

## A/B/C/D 四级基线

| 级别 | 路径 | 指标 |
| --- | --- | --- |
| A | MemTable 直连 | PUT 吞吐 > 50K ops/s；GET P99 < 5ms |
| B | CommandEngine 命令链 | SET 吞吐 > 30K ops/s；GET P99 < 10ms |
| C | WAL + SSTable + mmap | PUT > 20K ops/s；GET P99 < 50ms |
| D | 联邦仲裁 + 多智能体 + 硬件时钟（进程内模拟） | 提交 P99 < 1ms；多智能体 > 10K/s |

延迟指标统一口径：GET/SET P50 / P95 / P99（微秒/毫秒级，按级别注明）。

## 对比 TiKV（公开口径，仅参考）

| 指标 | TiKV 公开参考 | Tiering-KV 本地进程内（A/B/C/D） |
| --- | --- | --- |
| 单点读延迟 | 亚毫秒 ~ 毫秒级 | A 级 P99 微秒级 |
| 写吞吐 | 万 ~ 十万 ops/s | C/D 级 buffered WAL 十万级 |
| 冷读 | block cache / page cache | C/D 级 mmap |
| 跨机 RTT/RTO/RPO | 多副本跨机 | 跨机待执行（BM-002） |
| 冲突率/收敛时间 | 多主复制 | 跨机待执行 |

## 回归闭环机制

- 定期回归：多机部署脚本 + 对比表快照（吞吐 / 延迟 / 内存）+
  趋势记录；
- 偏离基线超过阈值 → 告警 → 自动重跑确认；
- 每阶段全量回归基准：`PHASE48-BASELINE` 输出。
