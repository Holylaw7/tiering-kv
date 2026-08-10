package io.tieringkv.cluster;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * 集群指标注册表（ADR-0056）：Raft 提案/提交延迟/复制滞后、
 * 迁移速率/游标/剩余量、证书过期时间。
 */
public final class ClusterMetricsRegistry {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final LongAdder proposals = new LongAdder();
    private final LongAdder commits = new LongAdder();
    private final LongAdder commitLatencyNanos = new LongAdder();
    private final RateWindow migrationRate = new RateWindow();
    private final long startedAt = System.currentTimeMillis();

    private volatile long replicationLag;
    private volatile long migrationCursor;
    private volatile long migrationRemaining;
    private volatile long certificateExpireMillis;

    public void recordProposal() {
        proposals.increment();
    }

    public void recordCommitLatency(long latencyNanos) {
        commitLatencyNanos.add(latencyNanos);
        commits.increment();
    }

    public void setReplicationLag(long lag) {
        this.replicationLag = Math.max(0, lag);
    }

    public void recordMigrationBytes(long bytes) {
        migrationRate.record(bytes);
    }

    public void setMigrationCursor(long cursor) {
        this.migrationCursor = Math.max(0, cursor);
    }

    public void setMigrationRemaining(long remaining) {
        this.migrationRemaining = Math.max(0, remaining);
    }

    public void setCertificateExpireMillis(long millis) {
        this.certificateExpireMillis = Math.max(0, millis);
    }

    public Snapshot snapshot() {
        long commitCount = commits.sum();
        double commitLatencyMs = commitCount == 0
                ? 0
                : commitLatencyNanos.sum() / (double) commitCount / 1_000_000.0;
        double proposalQps = proposals.sum() * 1000.0
                / Math.max(1, System.currentTimeMillis() - startedAt);
        return new Snapshot(
                proposalQps,
                commitLatencyMs,
                replicationLag,
                migrationRate.bytesPerSecond(),
                migrationCursor,
                migrationRemaining,
                certificateExpireMillis);
    }

    /** 指标文本（不含 section 头），供 INFO CLUSTER 组合。 */
    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "raft_proposal_qps:%.1f\r\n"
                        + "raft_commit_latency_ms:%.3f\r\n"
                        + "raft_replication_lag:%d\r\n"
                        + "migration_speed_bytes_per_sec:%.1f\r\n"
                        + "migration_cursor:%d\r\n"
                        + "migration_remaining:%d\r\n"
                        + "certificate_expire_time_ms:%d\r\n",
                s.raftProposalQps(),
                s.raftCommitLatencyMs(),
                s.raftReplicationLag(),
                s.migrationSpeedBytesPerSec(),
                s.migrationCursor(),
                s.migrationRemaining(),
                s.certificateExpireMillis());
    }

    public String sectionText() {
        return "# Cluster\r\n" + metricLines();
    }

    public record Snapshot(
            double raftProposalQps,
            double raftCommitLatencyMs,
            long raftReplicationLag,
            double migrationSpeedBytesPerSec,
            long migrationCursor,
            long migrationRemaining,
            long certificateExpireMillis) {
    }

    /** 1~2 秒滑动窗口速率：当前秒 + 上一秒加权。 */
    private static final class RateWindow {

        private long currentBytes;
        private long previousBytes;
        private long currentStartNanos = System.nanoTime();
        private long previousStartNanos = currentStartNanos - WINDOW_NANOS;

        private synchronized void record(long bytes) {
            long now = System.nanoTime();
            roll(now);
            currentBytes += bytes;
        }

        private synchronized double bytesPerSecond() {
            long now = System.nanoTime();
            roll(now);
            double currentElapsed = (now - currentStartNanos) / 1_000_000_000.0;
            double previousElapsed = Math.min(
                    1.0, (currentStartNanos - previousStartNanos) / 1_000_000_000.0);
            double window = Math.max(0.1, currentElapsed + previousElapsed);
            return (currentBytes + previousBytes) / window;
        }

        private void roll(long now) {
            if (now - currentStartNanos >= WINDOW_NANOS) {
                previousBytes = currentBytes;
                previousStartNanos = currentStartNanos;
                currentBytes = 0;
                currentStartNanos = now;
            }
        }
    }
}
