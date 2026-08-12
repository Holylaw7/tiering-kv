# 跨云物化视图指南（ADR-0158）

## 使用

```java
manager.create(new Definition("revenue-view", shards,
        Aggregate.SUM, refreshPeriodMillis));
manager.refresh("revenue-view", coordinatorCloud, shardExecutor);
manager.refreshIfDue("revenue-view", coordinatorCloud, shardExecutor);
Snapshot snapshot = manager.query("revenue-view");
manager.invalidate("revenue-view"); // 强制 stale
```

## 语义

- 新建视图 stale=true，刷新后 stale=false；
- 周期到期自动刷新；stale 视图忽略周期立即刷新；
- 跨驻留边界刷新默认拒绝（SecurityException）；
- 刷新失败保留旧快照（不覆盖）。
