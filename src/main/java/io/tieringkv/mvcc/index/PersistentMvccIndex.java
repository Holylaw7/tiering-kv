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
