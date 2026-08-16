package io.tieringkv.observability;

import io.tieringkv.cluster.metrics.MetricsExporter;

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
        return MetricsExporter.exportAll(
                vector, replication, multimodel, backup, tracing);
    }
}
