# Task: Phase 4 — Benchmark 基准

状态：⏳ 未开始

## 目标

建立压测体系：延迟 / 吞吐 / 内存 / 迁移，与纯内存 Redis 对比。

## 交付物

- benchmarks/{throughput,latency,memory,migration}；
- JMH 与连接压测工具；
- docs/benchmark/{latency,memory,concurrency}-report.md；
- 性能回归门禁接入 CI。

## 验收

- 热点 GET P50 < 0.5ms（P95/P99 基线）；
- 1k / 10k / 100k 连接测试；
- 内存占用较纯内存 Redis 降低 60%–80%。

## 关联

- ROADMAP Phase 9；docs/benchmark/benchmark-plan.md。
