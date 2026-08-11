# 负载驱动自动重分片

Phase 31 · ADR-0132

## 架构

```text
LoadProbe（QPS/延迟/分片大小）
  → AutoReshardController（阈值判定 + 冷却 + 熔断）
  → SPLIT / MERGE / NOOP
```

## 策略

- `splitQpsThreshold`：超过 → SPLIT；
- `mergeQpsThreshold`：低于 → MERGE；
- `cooldownMillis`：冷却窗口防抖动；
- `maxFailures`：连续失败熔断（停止触发，不放大故障）。

## 基准（进程内）

判定 1–10M ops/s。

## 限制

- 迁移执行复用 Phase 30 ShardMigration（本阶段为触发闭环）；
- 策略参数需生产校准。
