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

    public ObservabilityRegistry(VectorMetricsRegistry vector,
                                 ReplicationMetricsRegistry replication,
                                 MultiModelMetricsRegistry multimodel,
                                 BackupMetricsRegistry backup) {
        this.vector = vector;
        this.replication = replication;
        this.multimodel = multimodel;
        this.backup = backup;
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

    /** INFO sections：vector/replication/multimodel/backup。 */
    public Map<String, Supplier<String>> infoSections() {
        return Map.of(
                "vector", () -> "# Vector\r\n" + vector.metricLines(),
                "replication",
                () -> "# Replication\r\n" + replication.metricLines(),
                "multimodel",
                () -> "# MultiModel\r\n" + multimodel.metricLines(),
                "backup", () -> "# Backup\r\n" + backup.metricLines());
    }

    /** Prometheus 文本（与 INFO 同一 snapshot 渲染）。 */
    public String prometheusText() {
        return MetricsExporter.exportAll(
                vector, replication, multimodel, backup);
    }
}
