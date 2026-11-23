package io.tieringkv.mvcc;

import java.util.Map;

/** Snapshot 读（ADR-0071）：只读 commitTS <= readTS 的已提交版本。 */
public final class SnapshotReader {

    public byte[] get(MvccStorageEngine engine, byte[] key, long readTS) {
        MvccEntry entry = engine.read(key, readTS);
        return entry == null || entry.isDelete() ? null : entry.value();
    }

    public Map<byte[], byte[]> scan(MvccStorageEngine engine,
                                    byte[] startKey, byte[] endKey,
                                    long readTS) {
        return engine.scan(startKey, endKey, readTS);
    }
}
