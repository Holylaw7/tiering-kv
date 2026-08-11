# PITR 保留策略

Phase 27 · ADR-0111

## 1. 策略

```text
RetentionPolicy(maxSegments, maxAgeMillis, minSafeWatermark)
```

- 段数量超限且超出时间窗口 → 删除；
- 段内最小 seq <= 最新 checkpoint 水位 → 永不删除；
- maxAgeMillis=0 表示不按时间保留。

## 2. 清理

`ArchiveLifecycleManager.cleanup()` 返回被删除段名；恢复语义不受影响
（最新 checkpoint + 剩余归档仍可 PITR）。

## 3. 限制

- 早于保留窗口的时间点不可恢复（预期行为）；
- 并发 checkpoint 与清理依赖原子水位读取（当前为文件级）。
