package io.tieringkv.mvcc.index;

import io.tieringkv.mvcc.MvccEntry;
import io.tieringkv.mvcc.MvccKey;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 持久化 MVCC 索引门面（ADR-0080）：save / load / restore / 增量重建。
 */
public final class PersistentMvccIndex {

    private PersistentMvccIndex() {
    }

    /** 从引擎内存索引生成快照。 */
    public static MvccIndexSnapshot snapshot(MvccStorageEngine engine) {
        List<MvccEntry> versions = new ArrayList<>();
        for (List<MvccEntry> group : engine.versionGroups().values()) {
            versions.addAll(group);
        }
        return MvccIndexSnapshot.of(versions);
    }

    public static void save(Path path, MvccIndexSnapshot snapshot)
            throws IOException {
        MvccIndexWriter.write(path, snapshot);
    }

    /** 快照字节（ADR-0104）：PITR 检查点复用 v1 索引格式。 */
    public static byte[] snapshotBytes(MvccStorageEngine engine)
            throws IOException {
        Path temp = java.nio.file.Files.createTempFile(
                "mvcc-snapshot", ".idx");
        try {
            MvccIndexWriter.write(temp, snapshot(engine));
            return java.nio.file.Files.readAllBytes(temp);
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    /** 从字节恢复（ADR-0104）：PITR 时间线恢复入口。 */
    public static MvccStorageEngine restoreBytes(
            byte[] snapshot, StorageEngine storage) throws IOException {
        Path temp = java.nio.file.Files.createTempFile(
                "mvcc-restore", ".idx");
        try {
            java.nio.file.Files.write(temp, snapshot);
            return restore(temp, storage);
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    public static MvccIndexSnapshot load(Path path) throws IOException {
        return MvccIndexReader.read(path);
    }

    /** 快照恢复：从持久化索引构建引擎，保留全部历史版本。 */
    public static MvccStorageEngine restore(Path path, StorageEngine storage)
            throws IOException {
        MvccIndexSnapshot snapshot = load(path);
        return MvccStorageEngine.fromIndex(storage, snapshot.versions());
    }

    /**
     * 增量重建：加载快照 → 扫描底层存储，仅追加 commitTS 超过快照水位的
     * 版本（等价于快照后的 WAL replay）。
     */
    public static MvccStorageEngine restoreIncremental(
            Path path, StorageEngine storage) throws IOException {
        MvccIndexSnapshot base = load(path);
        List<MvccEntry> versions = new ArrayList<>(base.versions());
        long cutoff = base.maxCommitTS();
        try (StorageIterator iterator = storage.iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                long commitTS = MvccKey.commitTS(entry.key());
                if (commitTS > cutoff) {
                    versions.add(new MvccEntry(MvccKey.userKey(entry.key()),
                            entry.value(), MvccKey.startTS(entry.key()),
                            commitTS, MvccKey.writeType(entry.key())));
                }
            }
        }
        return MvccStorageEngine.fromIndex(storage, versions);
    }
}
