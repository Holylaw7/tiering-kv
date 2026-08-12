# 物化视图生命周期指南（ADR-0181）

## 使用

```java
MaterializedViewLifecycle lifecycle = new MaterializedViewLifecycle();
boolean expired = lifecycle.expired(snapshot, ttlMillis, now);
ArchivedView archived = lifecycle.archive(snapshot, now);
RemoteSnapshot restored = lifecycle.restore(archived);
List<String> expiredViews = lifecycle.sweep(manager, ttl, now);
```

归档保留完整快照（含 stale 标记），可无损恢复；sweep 只扫描不删除。
