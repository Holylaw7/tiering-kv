package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.storage.StorageEngine;

/**
 * 跨集群目标端（ADR-0321）：LWW 决策后应用到本地 StorageEngine。
 * 被裁决丢弃的事件不落盘。
 */
public final class CrossClusterSink {

    private final StorageEngine storage;
    private final LwwConflictResolver resolver;
    private final CrossClusterWatermark watermark;

    public CrossClusterSink(StorageEngine storage,
                            LwwConflictResolver resolver) {
        this(storage, resolver, null);
    }

    public CrossClusterSink(StorageEngine storage,
                            LwwConflictResolver resolver,
                            CrossClusterWatermark watermark) {
        if (storage == null || resolver == null) {
            throw new IllegalArgumentException(
                    "storage and resolver required");
        }
        this.storage = storage;
        this.resolver = resolver;
        this.watermark = watermark;
    }

    /** 返回 true 表示事件被接受并应用。 */
    public boolean apply(ChangeEvent event, String originClusterId) {
        if (watermark != null
                && watermark.shouldSkip(event.regionId(),
                event.seq())) {
            return false;
        }
        if (!resolver.accept(event, originClusterId)) {
            return false;
        }
        if (event.deleted()
                || event.type() == ChangeEvent.EventType.DELETE) {
            storage.delete(event.key());
        } else {
            storage.put(event.key(), event.value());
        }
        if (watermark != null) {
            watermark.record(event.regionId(), event.seq());
        }
        return true;
    }

    public LwwConflictResolver resolver() {
        return resolver;
    }
}
