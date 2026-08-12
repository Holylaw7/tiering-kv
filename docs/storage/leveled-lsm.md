# 生产级 LSM 演进指南（ADR-0204）

## Leveled Compaction

```java
LeveledCompactionPlanner planner = new LeveledCompactionPlanner();
boolean compact = planner.shouldCompact(totalBytes, maxBytes);
CompactionPlan plan = planner.planLevel(totalBytes, maxBytes,
        fileMaxBytes, level);
// sourceLevel → targetLevel（L0→L1→L2）
```

## Immutable 轮转

```java
ImmutableMemTableRotator rotator = new ImmutableMemTableRotator();
String newActive = rotator.rotate();   // Active → Immutable
rotator.flushDone(immutableId);        // Flush 完成移除
```

保持 SSTable 格式兼容（零回退）。
