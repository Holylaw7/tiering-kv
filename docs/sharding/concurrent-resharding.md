# 并发自动重分片

Phase 32 · ADR-0140

## 执行

```text
ConcurrentReshardExecutor(workers, maxMovesPerTick)
  → 按批分配键（每批 ≤ maxMovesPerTick）
  → 多线程迁移（ConcurrentHashMap 源/目标）
  → 合并回调用方 Map
```

## 语义

- 限速（每 tick 批大小）；
- 并发安全（内部 CHM，无 CME/丢失）；
- 返回迁移数量，失败回滚由调用方（ShardRouter）负责。

## 基准（进程内）

1K–10K 键 34K–1.25M ops/s。
