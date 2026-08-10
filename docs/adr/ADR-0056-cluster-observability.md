# ADR-0056: Cluster Observability

## Status

Accepted

## Context

Phase 14 有 StorageMetrics 但缺少 Raft/迁移/安全运行指标与集群级
INFO 视图。

## Problem

- 需要 raft_proposal_qps / raft_commit_latency / raft_replication_lag；
- 需要 migration_speed / migration_cursor / migration_remaining；
- 需要 certificate_expire_time；
- 需要 `INFO CLUSTER`（node/role/term/leader/slot）。

## Options

1. **无观测（现状）**：无法排障；
2. **Registry 指标 + INFO CLUSTER（选定）**：轻量自研，不引外部依赖。

## Decision

采用 **ClusterMetricsRegistry + INFO CLUSTER**：

```text
RaftMetrics（proposal_qps / commit_latency / lag）
MigrationMetrics（speed / cursor / remaining）
SecurityMetrics（certificate_expire_time）
INFO CLUSTER → node/role/term/leader/slot 摘要
```

## Consequences

**优点：** 运行可观测、故障定位快；
**缺点：** 指标采集有少量开销（计数/EMA，可忽略）。

## Implementation

- `io.tieringkv.cluster.metrics`：ClusterMetricsRegistry / RaftMetrics /
  MigrationMetrics / SecurityMetrics；
- INFO 命令增加 CLUSTER 段。
