# 分布式可观测性与告警

Phase 29 · Goal 7

## 指标

```text
dist_sql_query_p99 / vector_shard_skew / crdt_clock_skew
global_read_staleness / meter_usage
```

## 告警

- AlertRule：metric + threshold + 方向 + 等级（WARN/CRITICAL）；
- AlertManager：快照评估 → 告警列表；
- 覆盖：复制滞后、时钟偏差、配额超限、分片倾斜。

## 追踪

跨 Region 查询 span（queryId 关联）为 Phase 30 深化项。
