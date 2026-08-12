# 跨云远端物化指南（ADR-0173）

## 模型

```text
RemoteDefinition（远端云 + 协调器云 + 分片 + 聚合）
  → 主权校验（单驻留）
  → 远端快照（stale 标记）
  → CDC 增量同步 / 全量刷新回退
```

## 使用

```java
manager.define(new RemoteDefinition("v1", "gcp-us", "aws-us",
        shards, Aggregate.SUM));
manager.syncChange("v1", new CdcChange("k1", ChangeType.INSERT, 10));
manager.refreshFull("v1", shardExecutor);
```

跨驻留（协调器/远端/分片驻留不一致）默认拒绝（SecurityException）。
