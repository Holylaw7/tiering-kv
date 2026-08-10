package io.tieringkv.mvcc;

import java.util.List;
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
        try (io.tieringkv.storage.StorageIterator iterator =
                     engine.underlying().iterator()) {
            byte[] currentUser = null;
            int count = 0;
            byte[] currentKey = null;
            long currentCommit = -1;
            while (iterator.hasNext()) {
                io.tieringkv.storage.memory.KeyValueEntry entry = iterator.next();
                byte[] userKey = MvccKey.userKey(entry.key());
                if (currentUser == null
                        || !java.util.Arrays.equals(userKey, currentUser)) {
                    // 切换用户键：保留上一键的最新版本
                    if (currentUser != null && currentKey != null && count > 1
                            && safePoint.canCollect(currentCommit)) {
                        engine.deleteVersion(currentUser, currentCommit);
                        collected++;
                        bytes += entryBytes(entry.key(), entry.value());
                    }
                    currentUser = userKey;
                    count = 1;
                    currentKey = null;
                    currentCommit = -1;
                } else {
                    count++;
                }
                long commit = MvccKey.commitTS(entry.key());
                if (safePoint.canCollect(commit) && count > 1) {
                    engine.deleteVersion(userKey, commit);
                    collected++;
                    bytes += entryBytes(entry.key(), entry.value());
                } else {
                    currentKey = entry.key();
                    currentCommit = commit;
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
