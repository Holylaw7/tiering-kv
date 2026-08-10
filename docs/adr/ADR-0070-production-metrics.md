# ADR-0070: Production Metrics

## Status

Accepted

## Context

生产可观测性需要统一指标出口：INFO CLUSTER 覆盖 Region/Raft/Migration/
Gateway，并输出 Prometheus 兼容格式。

## Decision

- INFO CLUSTER 聚合：
  - Region：region_count / split_count / merge_count；
  - Raft：leader / term / commit_index / replication_lag；
  - Migration：migration_speed / migration_remaining / error；
  - Gateway：connection / qps / latency；
- `MetricsExporter`：Prometheus text format
  （`# HELP` / `# TYPE` + `name{label="v"} value`）；
- 指标注册表：RegionMetricsRegistry / RaftMetricsRegistry /
  MigrationMetricsRegistry / GatewayMetricsRegistry（新增）；
- 口径：计数器单调递增，仪表盘取瞬时值；延迟为均值 + 分位（网关）。

## Alternatives

1. 仅 INFO 文本：无法接入 Prometheus，否决。
2. 引入 Micrometer/Prometheus SDK：依赖膨胀，本阶段自研轻量导出，
  后续可替换。

## Consequences

优点：INFO 与 Prometheus 双出口；指标语义一致。

缺点：分位统计为近似实现；跨进程聚合未做。

风险：指标高频更新有原子开销（LongAdder 可控）。

## Implementation

- `cluster/metrics/GatewayMetricsRegistry.java`、
  `cluster/metrics/MetricsExporter.java`
- INFO CLUSTER 扩展 + 测试（≥10）。
