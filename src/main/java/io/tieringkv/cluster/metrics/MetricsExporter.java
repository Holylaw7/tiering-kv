package io.tieringkv.cluster.metrics;

import io.tieringkv.cluster.migration.MigrationMetricsRegistry;
import io.tieringkv.cluster.raft.RaftMetricsRegistry;
import io.tieringkv.cluster.region.RegionMetricsRegistry;

import java.util.Locale;

/** Prometheus 兼容指标导出（ADR-0070）：HELP/TYPE + name{label} value。 */
public final class MetricsExporter {

    private MetricsExporter() {
    }

    public static String export(RegionMetricsRegistry regions,
                                RaftMetricsRegistry raft,
                                MigrationMetricsRegistry migration,
                                GatewayMetricsRegistry gateway) {
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
