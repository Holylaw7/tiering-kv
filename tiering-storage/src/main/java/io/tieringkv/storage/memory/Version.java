package io.tieringkv.storage.memory;

import java.util.concurrent.atomic.AtomicLong;

/** 全局单调版本号：为 WAL 排序、TTL 守卫、Snapshot/LSM 提供写入顺序（ADR-0007）。 */
public final class Version {

    private final AtomicLong sequence = new AtomicLong();

    public long next() {
        return sequence.incrementAndGet();
    }
}
