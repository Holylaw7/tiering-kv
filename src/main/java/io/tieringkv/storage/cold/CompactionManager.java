package io.tieringkv.storage.cold;

import java.io.IOException;

/** 合并调度（ADR-0019）：表数达阈值触发全量合并。 */
public final class CompactionManager {

    private final ColdStorageEngine engine;

    CompactionManager(ColdStorageEngine engine) {
        this.engine = engine;
    }

    public boolean compactIfNeeded() {
        return engine.compactIfNeeded();
    }

    public SSTableMeta compactAll() throws IOException {
        return engine.compactAll();
    }
}
