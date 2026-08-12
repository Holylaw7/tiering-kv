# CDC 增量物化视图指南（ADR-0166）

## 使用

```java
CdcMaterializedViewRefresher refresher =
        new CdcMaterializedViewRefresher();
refresher.apply(manager, "v1", Aggregate.SUM,
        new CdcChange("k1", ChangeType.INSERT, 10));
refresher.apply(manager, "v1", Aggregate.SUM,
        new CdcChange("k1", ChangeType.UPDATE, 20));
refresher.apply(manager, "v1", Aggregate.SUM,
        new CdcChange("k1", ChangeType.DELETE, 0));
refresher.refreshFull(manager, "v1", "aws-us", executor);
```

## 语义

- INSERT/UPDATE 写入 key 状态；DELETE 移除；
- 聚合按当前 key 集合重算（SUM/COUNT/AVG/MIN/MAX）；
- 失败回退全量刷新并标记 stale；增量状态清空。
