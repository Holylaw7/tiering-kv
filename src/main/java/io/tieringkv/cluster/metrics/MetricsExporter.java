package io.tieringkv.cluster.metrics;

import io.tieringkv.cluster.migration.MigrationMetricsRegistry;
import io.tieringkv.cluster.raft.RaftMetricsRegistry;
import io.tieringkv.cluster.region.RegionMetricsRegistry;
import io.tieringkv.mvcc.MvccMetricsRegistry;
import io.tieringkv.mvcc.TransactionMetricsRegistry;

import java.util.Locale;

/** Prometheus 兼容指标导出（ADR-0070）：HELP/TYPE + name{label} value。 */
public final class MetricsExporter {

    private MetricsExporter() {
    }

    public static String export(RegionMetricsRegistry regions,
                                RaftMetricsRegistry raft,
                                MigrationMetricsRegistry migration,
                                GatewayMetricsRegistry gateway) {
        return export(regions, raft, migration, gateway,
                new TransactionMetricsRegistry(), new MvccMetricsRegistry());
    }

    public static String export(RegionMetricsRegistry regions,
                                RaftMetricsRegistry raft,
                                MigrationMetricsRegistry migration,
                                GatewayMetricsRegistry gateway,
                                TransactionMetricsRegistry transactions,
                                MvccMetricsRegistry mvcc) {
        StringBuilder sb = new StringBuilder();
        RegionMetricsRegistry.Snapshot r = regions.snapshot();
        RaftMetricsRegistry.Snapshot f = raft.snapshot();
        MigrationMetricsRegistry.Snapshot m = migration.snapshot();
        GatewayMetricsRegistry.Snapshot g = gateway.snapshot();
        counter(sb, "tiering_region_count", "current region count",
                r.regionCount());
        counter(sb, "tiering_region_split_total", "region splits",
                r.regionSplitCount());
        counter(sb, "tiering_region_merge_total", "region merges",
                r.regionMergeCount());
        gauge(sb, "tiering_region_size_bytes", "region size bytes",
                r.regionSizeBytes());
        counter(sb, "tiering_raft_leader_transfer_total",
                "leader transfers", f.leaderTransferTotal());
        counter(sb, "tiering_raft_election_total", "elections",
                f.electionTotal());
        gauge(sb, "tiering_raft_proposal_latency_ms",
                "proposal latency ms", f.proposalLatencyMs());
        counter(sb, "tiering_migration_bytes_total", "migrated bytes",
                m.migrationBytes());
        gauge(sb, "tiering_migration_speed_mb_per_sec", "migration speed",
                m.migrationSpeedMbPerSec());
        gauge(sb, "tiering_migration_remaining", "remaining entries",
                m.migrationRemaining());
        counter(sb, "tiering_migration_error_total", "migration errors",
                m.migrationErrors());
        gauge(sb, "tiering_gateway_connections", "gateway connections",
                g.connections());
        gauge(sb, "tiering_gateway_qps", "gateway qps", g.qps());
        gauge(sb, "tiering_gateway_avg_latency_ms", "gateway latency",
                g.avgLatencyMs());
        TransactionMetricsRegistry.Snapshot t = transactions.snapshot();
        counter(sb, "txn_begin_total", "transactions begun",
                t.beginTotal());
        counter(sb, "txn_commit_total", "transactions committed",
                t.committedTxn());
        counter(sb, "txn_rollback_total", "transactions rolled back",
                t.rollbackTxn());
        counter(sb, "txn_abort_total", "transactions aborted",
                t.abortTxn());
        counter(sb, "txn_conflict_total", "transaction conflicts",
                t.conflictTxn());
        counter(sb, "txn_recovery_total", "transactions recovered",
                t.recoveryTxn());
        gauge(sb, "txn_active", "active transactions", t.activeTxn());
        gauge(sb, "txn_lock_count", "held locks", t.lockCount());
        gauge(sb, "txn_commit_latency_ms", "commit latency ms",
                t.commitLatencyMs());
        gauge(sb, "txn_latency_ms", "transaction latency ms",
                t.commitLatencyMs());
        gauge(sb, "txn_prepare_latency_ms", "prepare latency ms",
                t.prepareLatencyMs());
        counter(sb, "txn_network_retry", "network retries",
                t.networkRetry());
        gauge(sb, "txn_lock_wait_ms", "lock wait ms", t.lockWaitMs());
        gauge(sb, "txn_region_count", "participating regions",
                t.regionCount());
        gauge(sb, "txn_recovery_time_ms", "recovery time ms",
                t.recoveryTimeMs());
        MvccMetricsRegistry.Snapshot v = mvcc.snapshot();
        gauge(sb, "mvcc_read_qps", "mvcc reads", v.reads());
        gauge(sb, "mvcc_write_qps", "mvcc writes", v.writes());
        gauge(sb, "mvcc_versions_total", "mvcc versions", v.versions());
        counter(sb, "mvcc_gc_versions_total", "gc versions",
                v.gcVersions());
        counter(sb, "mvcc_gc_deleted_versions", "gc deleted versions",
                v.gcVersions());
        counter(sb, "mvcc_compaction_versions", "compacted versions",
                v.compactionVersions());
        counter(sb, "mvcc_compaction_bytes", "compacted bytes",
                v.compactionBytes());
        gauge(sb, "mvcc_safe_point", "gc safe point", v.safePoint());
        gauge(sb, "redis_txn_latency_ms", "redis auto txn latency ms",
                g.transactionLatencyMs());
        return sb.toString();
    }

    private static void counter(StringBuilder sb, String name,
                                String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder sb, String name,
                              String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ')
                .append(String.format(Locale.ROOT, "%.3f", value)).append('\n');
    }
}
