# PITR 使用指南

Phase 26 · ADR-0104

## 1. 概念

```text
T0 snapshot（CheckpointManager）
        ↓
T1 WAL archive（WALArchiveManager / PitrWriteLog）
        ↓
T2 restore timestamp（RestoreTimeline）
```

## 2. 使用

```java
// 写入时旁路记录
MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archiveDir);
recorder.putVersion(key, value, startTS, commitTS, WriteType.PUT);

// 检查点
CheckpointManager.save(ckptDir, new CheckpointManager.Checkpoint(
        recorder.watermark(), timestamp,
        PersistentMvccIndex.snapshotBytes(engine)));

// 恢复
MvccStorageEngine restored = RestoreTimeline.restore(
        MemTable.create(), ckptDir, archiveDir, targetCommitTS);
```

## 3. 语义

- 重放仅 apply `seq > watermark` 且 `commitTS <= target` 的记录；
- tombstone 按 commitTS 参与可见性，旧 tombstone 不遮蔽新值；
- 重复恢复幂等（每次基于全新引擎）。

## 4. 限制

- 归档日志保留策略（删除旧段）未自动化，需运维定期清理；
- 跨机归档传输待 Phase 27。
