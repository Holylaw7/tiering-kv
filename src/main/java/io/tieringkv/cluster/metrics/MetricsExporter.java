package io.tieringkv.cluster.metrics;

import io.tieringkv.cluster.migration.MigrationMetricsRegistry;
import io.tieringkv.cluster.raft.RaftMetricsRegistry;
import io.tieringkv.cluster.region.RegionMetricsRegistry;
import io.tieringkv.mvcc.MvccMetricsRegistry;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.observability.BackupMetricsRegistry;
import io.tieringkv.observability.MultiModelMetricsRegistry;
import io.tieringkv.observability.ReplicationMetricsRegistry;
import io.tieringkv.observability.TracingMetricsRegistry;
import io.tieringkv.observability.VectorMetricsRegistry;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

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
        exportClusterTxnMvcc(sb, regions, raft, migration, gateway,
                transactions, mvcc);
        return sb.toString();
    }

    /**
     * 可观测性收口（ADR-0344）：在既有 Cluster/Txn/MVCC 基础上追加
     * 向量/复制/多模型/备份指标。与 INFO sections 同一 snapshot 渲染。
     */
    public static String exportAll(VectorMetricsRegistry vector,
                                   ReplicationMetricsRegistry replication,
                                   MultiModelMetricsRegistry multimodel,
                                   BackupMetricsRegistry backup) {
        return exportAll(vector, replication, multimodel, backup, null);
    }

    /** 可观测性收口（ADR-0345）：追加追踪指标（tracing 为 null 跳过）。 */
    public static String exportAll(VectorMetricsRegistry vector,
                                   ReplicationMetricsRegistry replication,
                                   MultiModelMetricsRegistry multimodel,
                                   BackupMetricsRegistry backup,
                                   TracingMetricsRegistry tracing) {
        StringBuilder sb = new StringBuilder();
        VectorMetricsRegistry.Snapshot v = vector.snapshot();
        gauge(sb, "vector_count", "current vector count",
                v.vectorCount());
        gauge(sb, "vector_dim", "vector dimension", v.dim());
        gauge(sb, "vector_max_level", "hnsw max level", v.maxLevel());
        counter(sb, "vector_writes_total", "vector writes",
                v.writes());
        counter(sb, "vector_deletes_total", "vector deletes",
                v.deletes());
        ReplicationMetricsRegistry.Snapshot r = replication.snapshot(
                System.currentTimeMillis());
        gauge(sb, "replication_replicas", "replica count",
                r.replicas());
        gauge(sb, "replication_max_lag_ms", "max replica lag ms",
                r.maxLagMillis());
        counter(sb, "replication_replicated_total", "replicated events",
                r.replicated());
        counter(sb, "replication_suppressed_total",
                "suppressed loopback events", r.suppressed());
        counter(sb, "replication_conflicts_total",
                "replication conflicts", r.conflicts());
        MultiModelMetricsRegistry.Snapshot m = multimodel.snapshot();
        counter(sb, "multimodel_json_writes_total", "json writes",
                m.jsonWrites());
        counter(sb, "multimodel_json_validation_errors_total",
                "json validation errors", m.jsonValidationErrors());
        counter(sb, "multimodel_ts_writes_total", "timeseries writes",
                m.tsWrites());
        counter(sb, "multimodel_bytes_total", "multimodel value bytes",
                m.multimodelBytes());
        BackupMetricsRegistry.Snapshot b = backup.snapshot();
        counter(sb, "backup_total", "backups", b.backups());
        counter(sb, "backup_bytes_total", "backup bytes",
                b.backupBytes());
        counter(sb, "restore_total", "restores", b.restores());
        counter(sb, "restore_bytes_total", "restore bytes",
                b.restoreBytes());
        gauge(sb, "backup_pitr_watermark", "pitr watermark",
                b.pitrWatermark());
        if (tracing != null) {
            TracingMetricsRegistry.Snapshot t = tracing.snapshot();
            gauge(sb, "tracing_spans", "sampled span count",
                    t.spans());
            gauge(sb, "tracing_avg_duration_nanos",
                    "average span duration nanos",
                    t.avgDurationNanos());
            gauge(sb, "tracing_max_duration_nanos",
                    "max span duration nanos",
                    t.maxDurationNanos());
        }
        return sb.toString();
    }

    private static void exportClusterTxnMvcc(
            StringBuilder sb, RegionMetricsRegistry regions,
            RaftMetricsRegistry raft, MigrationMetricsRegistry migration,
            GatewayMetricsRegistry gateway,
            TransactionMetricsRegistry transactions,
            MvccMetricsRegistry mvcc) {
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
        gauge(sb, "txn_long_running", "long running txns",
                t.longRunning());
        counter(sb, "txn_expired_total", "expired txns", t.expiredTotal());
        counter(sb, "lock_total", "locks acquired", t.lockTotal());
        counter(sb, "lock_resolve_total", "locks resolved",
                t.lockResolveTotal());
        gauge(sb, "lock_wait_time_ms", "lock wait ms", t.lockWaitMs());
        for (Map.Entry<String, LongAdder> reason : t.abortReasons().entrySet()) {
            counter(sb, "txn_abort_reason_" + reason.getKey(),
                    "abort reason", reason.getValue().sum());
        }
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
