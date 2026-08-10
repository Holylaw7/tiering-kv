package io.tieringkv.mvcc;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** MVCC 指标（Phase 19）：版本数/GC/安全点/读写 QPS。 */
public final class MvccMetricsRegistry {

    private final LongAdder reads = new LongAdder();
    private final LongAdder writes = new LongAdder();
    private final LongAdder gcVersions = new LongAdder();
    private final LongAdder gcBytes = new LongAdder();
    private final AtomicLong versions = new AtomicLong();
    private volatile long safePoint = Long.MIN_VALUE;

    public void recordRead() {
        reads.increment();
    }

    public void recordWrite() {
        writes.increment();
    }

    public void recordGc(long versions, long bytes) {
        gcVersions.add(versions);
        gcBytes.add(bytes);
    }

    public void setVersions(long count) {
        versions.set(count);
    }

    public void setSafePoint(long ts) {
        safePoint = ts;
    }

    public Snapshot snapshot() {
        return new Snapshot(versions.get(), gcVersions.sum(), gcBytes.sum(),
                safePoint, reads.sum(), writes.sum());
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "mvcc_versions_total:%d\r\n"
                        + "mvcc_gc_versions:%d\r\n"
                        + "mvcc_gc_bytes:%d\r\n"
                        + "mvcc_safe_point:%d\r\n"
                        + "mvcc_read_qps:%d\r\n"
                        + "mvcc_write_qps:%d\r\n",
                s.versions(), s.gcVersions(), s.gcBytes(), s.safePoint(),
                s.reads(), s.writes());
    }

    public record Snapshot(long versions, long gcVersions, long gcBytes,
                           long safePoint, long reads, long writes) {
    }
}
