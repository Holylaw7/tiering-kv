package io.tieringkv.observability;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 可观测性聚合（ADR-0344）：向量/复制/多模型/备份注册表 +
 * INFO sections + Prometheus 文本（单一数据源，避免双口径）。
 */
public final class ObservabilityRegistry {

    private final VectorMetricsRegistry vector;
    private final ReplicationMetricsRegistry replication;
    private final MultiModelMetricsRegistry multimodel;
    private final BackupMetricsRegistry backup;
    private final TracingMetricsRegistry tracing;

    public ObservabilityRegistry(VectorMetricsRegistry vector,
                                 ReplicationMetricsRegistry replication,
                                 MultiModelMetricsRegistry multimodel,
                                 BackupMetricsRegistry backup) {
        this(vector, replication, multimodel, backup, null);
    }

    /** 可观测性收口（ADR-0345）：可选追踪指标（tracing section）。 */
    public ObservabilityRegistry(VectorMetricsRegistry vector,
                                 ReplicationMetricsRegistry replication,
                                 MultiModelMetricsRegistry multimodel,
                                 BackupMetricsRegistry backup,
                                 TracingMetricsRegistry tracing) {
        this.vector = vector;
        this.replication = replication;
        this.multimodel = multimodel;
        this.backup = backup;
        this.tracing = tracing;
    }

    public VectorMetricsRegistry vector() {
        return vector;
    }

    public ReplicationMetricsRegistry replication() {
        return replication;
    }

    public MultiModelMetricsRegistry multimodel() {
        return multimodel;
    }

    public BackupMetricsRegistry backup() {
        return backup;
    }

    public TracingMetricsRegistry tracing() {
        return tracing;
    }

    /** INFO sections：vector/replication/multimodel/backup。 */
    public Map<String, Supplier<String>> infoSections() {
        Map<String, Supplier<String>> sections = new java.util.HashMap<>();
        sections.put("vector",
                () -> "# Vector\r\n" + vector.metricLines());
        sections.put("replication",
                () -> "# Replication\r\n" + replication.metricLines());
        sections.put("multimodel",
                () -> "# MultiModel\r\n" + multimodel.metricLines());
        sections.put("backup",
                () -> "# Backup\r\n" + backup.metricLines());
        sections.put("tracing", () -> "# Tracing\r\n"
                + (tracing == null ? "tracing_spans:0\r\n" : tracing.metricLines()));
        return Map.copyOf(sections);
    }

    /** Prometheus 文本（与 INFO 同一 snapshot 渲染）。 */
    public String prometheusText() {
        return exportAll(vector, replication, multimodel, backup, tracing);
    }

    /** 统一 Prometheus 渲染（原 MetricsExporter.exportAll 下沉实现，解除 server -> cluster 依赖）。 */
    public static String exportAll(VectorMetricsRegistry vector,
                                   ReplicationMetricsRegistry replication,
                                   MultiModelMetricsRegistry multimodel,
                                   BackupMetricsRegistry backup,
                                   TracingMetricsRegistry tracing) {
        StringBuilder sb = new StringBuilder();
        VectorMetricsRegistry.Snapshot v = vector.snapshot();
        gauge(sb, "vector_count", "current vector count", v.vectorCount());
        gauge(sb, "vector_dim", "vector dimension", v.dim());
        gauge(sb, "vector_max_level", "hnsw max level", v.maxLevel());
        counter(sb, "vector_writes_total", "vector writes", v.writes());
        counter(sb, "vector_deletes_total", "vector deletes", v.deletes());
        counter(sb, "vector_checkpoints_total", "vector checkpoints", v.checkpoints());
        gauge(sb, "vector_checkpoint_watermark", "latest checkpoint vector count", v.checkpointWatermark());
        ReplicationMetricsRegistry.Snapshot r = replication.snapshot(System.currentTimeMillis());
        gauge(sb, "replication_replicas", "replica count", r.replicas());
        gauge(sb, "replication_max_lag_ms", "max replica lag ms", r.maxLagMillis());
        counter(sb, "replication_replicated_total", "replicated events", r.replicated());
        counter(sb, "replication_suppressed_total", "suppressed loopback events", r.suppressed());
        counter(sb, "replication_conflicts_total", "replication conflicts", r.conflicts());
        MultiModelMetricsRegistry.Snapshot m = multimodel.snapshot();
        counter(sb, "multimodel_json_writes_total", "json writes", m.jsonWrites());
        counter(sb, "multimodel_json_validation_errors_total", "json validation errors", m.jsonValidationErrors());
        counter(sb, "multimodel_ts_writes_total", "timeseries writes", m.tsWrites());
        counter(sb, "multimodel_bytes_total", "multimodel value bytes", m.multimodelBytes());
        BackupMetricsRegistry.Snapshot b = backup.snapshot();
        counter(sb, "backup_total", "backups", b.backups());
        counter(sb, "backup_bytes_total", "backup bytes", b.backupBytes());
        counter(sb, "restore_total", "restores", b.restores());
        counter(sb, "restore_bytes_total", "restore bytes", b.restoreBytes());
        gauge(sb, "backup_pitr_watermark", "pitr watermark", b.pitrWatermark());
        gauge(sb, "backup_replication_max_lag_ms", "max replica lag at backup", b.replicationMaxLagMillis());
        if (tracing != null) {
            TracingMetricsRegistry.Snapshot t = tracing.snapshot();
            gauge(sb, "tracing_spans", "sampled span count", t.spans());
            gauge(sb, "tracing_avg_duration_nanos", "average span duration nanos", t.avgDurationNanos());
            gauge(sb, "tracing_max_duration_nanos", "max span duration nanos", t.maxDurationNanos());
        }
        return sb.toString();
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ')
                .append(String.format(Locale.ROOT, "%.3f", value)).append('\n');
    }
}
