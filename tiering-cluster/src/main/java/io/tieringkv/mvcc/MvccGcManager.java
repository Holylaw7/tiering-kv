package io.tieringkv.mvcc;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** MVCC GC（ADR-0075）：回收 commitTS < safePoint 的非最新版本。 */
public final class MvccGcManager implements AutoCloseable {

    private final MvccStorageEngine engine;
    private volatile SafePoint safePoint = SafePoint.NONE;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();

    public MvccGcManager(MvccStorageEngine engine) {
        this.engine = engine;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mvcc-gc");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void updateSafePoint(SafePoint point) {
        this.safePoint = point;
    }

    public SafePoint safePoint() {
        return safePoint;
    }

    /** 手动 GC；返回 (回收版本数, 回收字节数)。 */
    public GcResult gc() {
        long collected = 0;
        long bytes = 0;
        Map<byte[], List<MvccEntry>> byKey = new TreeMap<>(
                java.util.Arrays::compareUnsigned);
        try (io.tieringkv.storage.StorageIterator iterator =
                     engine.underlying().iterator()) {
            while (iterator.hasNext()) {
                io.tieringkv.storage.memory.KeyValueEntry entry = iterator.next();
                byte[] userKey = MvccKey.userKey(entry.key());
                byKey.computeIfAbsent(userKey, ignored -> new ArrayList<>())
                        .add(new MvccEntry(userKey, entry.value(),
                                MvccKey.startTS(entry.key()),
                                MvccKey.commitTS(entry.key()),
                                MvccKey.writeType(entry.key())));
            }
        }
        for (List<MvccEntry> versions : byKey.values()) {
            versions.sort(Comparator.comparingLong(MvccEntry::commitTS));
            for (int i = 0; i < versions.size() - 1; i++) {
                MvccEntry version = versions.get(i);
                if (safePoint.canCollect(version.commitTS())) {
                    engine.deleteVersion(version.key(), version.commitTS());
                    collected++;
                    bytes += entryBytes(version.key(), version.value());
                }
            }
        }
        return new GcResult(collected, bytes);
    }

    public void startScheduled(long intervalMillis) {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(() -> {
                if (safePoint != SafePoint.NONE) {
                    gc();
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    private static long entryBytes(byte[] key, byte[] value) {
        return key.length + (value == null ? 0 : value.length);
    }

    public record GcResult(long collectedVersions, long collectedBytes) {
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
    }
}
