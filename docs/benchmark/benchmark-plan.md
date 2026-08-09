# Benchmark 计划（Benchmark Plan）

状态：基线（Phase 9 执行）

## 1. 目标

验证性能目标并建立回归门禁：

- 热点 GET P50 < 0.5ms（P95/P99 建立基线）；
- 1k / 10k / 100k 并发连接；
- 内存占用较纯内存 Redis 降低 60%–80%。

## 2. 工具

- JMH（微基准：编码、MemTable、存储引擎）；
- 自研连接压测客户端 / redis-benchmark（协议兼容验证）；
- JFR / GC 日志（内存与延迟归因）。

## 3. 场景

| 场景 | 说明 |
| --- | --- |
| 热点读 | 少量 key 高并发 GET |
| 冷读 | 数据在冷层，读后升热 |
| 写密集 | SET 压测 + WAL 开启/关闭对比 |
| 混合 | GET/SET/DEL 按 Redis 典型比例 |
| 迁移 | 持续访问下的冷热迁移 |

## 4. 报告

- latency-report.md、memory-report.md、concurrency-report.md（Phase 9 填充）；
- 每次发布前运行基准，劣化超过阈值视为阻塞（RELEASE_RULES）。
