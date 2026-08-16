package io.tieringkv.observability;

import io.tieringkv.replication.LagTracker;
import io.tieringkv.observability.tracing.TraceExporter;
import io.tieringkv.observability.tracing.Tracer;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 可观测性收口（ADR-0344）：4 个注册表 + 聚合 + INFO/Prometheus 渲染。 */
class ObservabilityRegistryTest {

    @Test
    void vectorMetricsExposeStoreStateAndCounters() {
        VectorIndexStore store = new VectorIndexStore(4);
        store.put(new Embedding("a", new float[]{1, 0, 0}));
        store.put(new Embedding("b", new float[]{0, 1, 0}));
        VectorMetricsRegistry metrics = new VectorMetricsRegistry(store);
        metrics.recordVectorWrite();
        metrics.recordVectorDelete();

        VectorMetricsRegistry.Snapshot s = metrics.snapshot();
        assertThat(s.vectorCount()).isEqualTo(2);
        assertThat(s.dim()).isEqualTo(3);
        assertThat(s.maxLevel()).isEqualTo(4);
        assertThat(s.writes()).isEqualTo(1);
        assertThat(s.deletes()).isEqualTo(1);
        assertThat(metrics.metricLines()).contains("vector_count:2")
                .contains("vector_dim:3")
                .contains("vector_max_level:4")
                .contains("vector_writes:1")
                .contains("vector_deletes:1");
    }

    @Test
    void replicationMetricsExposeLagAndCounters() {
        LagTracker lagTracker = new LagTracker();
        ReplicationMetricsRegistry metrics =
                new ReplicationMetricsRegistry(lagTracker);
        metrics.applied("r1", 10);
        metrics.recordReplicated();
        metrics.recordReplicated();
        metrics.recordSuppressed();
        metrics.recordConflict();

        ReplicationMetricsRegistry.Snapshot s = metrics.snapshot(
                System.currentTimeMillis());
        assertThat(s.replicas()).isEqualTo(1);
        assertThat(s.maxLagMillis()).isZero();
        assertThat(s.replicated()).isEqualTo(2);
        assertThat(s.suppressed()).isEqualTo(1);
        assertThat(s.conflicts()).isEqualTo(1);
        assertThat(metrics.metricLines()).contains("replication_replicas:1")
                .contains("replication_replicated:2")
                .contains("replication_suppressed:1")
                .contains("replication_conflicts:1");
    }

    @Test
    void multimodelMetricsExposeCounters() {
        MultiModelMetricsRegistry metrics = new MultiModelMetricsRegistry();
        metrics.recordJsonWrite();
        metrics.recordJsonWrite();
        metrics.recordJsonValidationError();
        metrics.recordTsWrite();
        metrics.recordTsWrite();
        metrics.recordTsWrite();
        metrics.recordMultiModelBytes(4096);

        MultiModelMetricsRegistry.Snapshot s = metrics.snapshot();
        assertThat(s.jsonWrites()).isEqualTo(2);
        assertThat(s.jsonValidationErrors()).isEqualTo(1);
        assertThat(s.tsWrites()).isEqualTo(3);
        assertThat(s.multimodelBytes()).isEqualTo(4096);
        assertThat(metrics.metricLines()).contains("multimodel_json_writes:2")
                .contains("multimodel_json_validation_errors:1")
                .contains("multimodel_ts_writes:3")
                .contains("multimodel_bytes:4096");
    }

    @Test
    void backupMetricsExposeCountersAndWatermark() {
        BackupMetricsRegistry metrics = new BackupMetricsRegistry();
        metrics.recordBackup(2048);
        metrics.recordRestore(1024);
        metrics.setPitrWatermark(99);

        BackupMetricsRegistry.Snapshot s = metrics.snapshot();
        assertThat(s.backups()).isEqualTo(1);
        assertThat(s.backupBytes()).isEqualTo(2048);
        assertThat(s.restores()).isEqualTo(1);
        assertThat(s.restoreBytes()).isEqualTo(1024);
        assertThat(s.pitrWatermark()).isEqualTo(99);
        assertThat(metrics.metricLines()).contains("backup_total:1")
                .contains("backup_bytes:2048")
                .contains("restore_total:1")
                .contains("restore_bytes:1024")
                .contains("backup_pitr_watermark:99");
    }

    @Test
    void infoSectionsExposeFourAggregatedSections() {
        ObservabilityRegistry registry = registryWithFeeds();
        assertThat(registry.infoSections().keySet())
                .containsExactlyInAnyOrder(
                        "vector", "replication", "multimodel", "backup",
                        "tracing");
        assertThat(registry.infoSections().get("vector").get())
                .startsWith("# Vector\r\n")
                .contains("vector_count:2");
        assertThat(registry.infoSections().get("replication").get())
                .startsWith("# Replication\r\n")
                .contains("replication_conflicts:1");
        assertThat(registry.infoSections().get("multimodel").get())
                .startsWith("# MultiModel\r\n")
                .contains("multimodel_ts_writes:3");
        assertThat(registry.infoSections().get("backup").get())
                .startsWith("# Backup\r\n")
                .contains("backup_pitr_watermark:99");
        assertThat(registry.infoSections().get("tracing").get())
                .startsWith("# Tracing\r\n")
                .contains("tracing_spans:0");
    }

    @Test
    void prometheusTextEmitsAllFamilies() {
        String text = registryWithFeeds().prometheusText();
        assertThat(text)
                .contains("# HELP vector_count")
                .contains("vector_count 2.000")
                .contains("# HELP replication_conflicts_total")
                .contains("replication_conflicts_total 1")
                .contains("# HELP multimodel_json_writes_total")
                .contains("multimodel_json_writes_total 2")
                .contains("# HELP backup_pitr_watermark")
                .contains("backup_pitr_watermark 99.000");
    }

    @Test
    void tracingMetricsExposeExporterStats() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(new Tracer.Span("t1", "s1", "", "op", 0, 100));
        exporter.export(new Tracer.Span("t2", "s2", "", "op", 0, 300));
        TracingMetricsRegistry tracing =
                new TracingMetricsRegistry(exporter);

        TracingMetricsRegistry.Snapshot s = tracing.snapshot();
        assertThat(s.spans()).isEqualTo(2);
        assertThat(s.avgDurationNanos()).isEqualTo(200);
        assertThat(s.maxDurationNanos()).isEqualTo(300);
        assertThat(tracing.metricLines()).contains("tracing_spans:2")
                .contains("tracing_avg_duration_nanos:200")
                .contains("tracing_max_duration_nanos:300");
    }

    @Test
    void tracingSectionAndPrometheusExposeSpans() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(new Tracer.Span("t1", "s1", "",
                "gateway:set", 0, 150));
        ObservabilityRegistry registry = new ObservabilityRegistry(
                new VectorMetricsRegistry(),
                new ReplicationMetricsRegistry(),
                new MultiModelMetricsRegistry(),
                new BackupMetricsRegistry(),
                new TracingMetricsRegistry(exporter));

        assertThat(registry.infoSections().keySet())
                .contains("tracing");
        assertThat(registry.infoSections().get("tracing").get())
                .startsWith("# Tracing\r\n")
                .contains("tracing_spans:1");
        assertThat(registry.prometheusText())
                .contains("# HELP tracing_spans")
                .contains("tracing_spans 1.000");
    }

    private static ObservabilityRegistry registryWithFeeds() {
        VectorIndexStore store = new VectorIndexStore(4);
        store.put(new Embedding("a", new float[]{1, 0}));
        store.put(new Embedding("b", new float[]{0, 1}));
        VectorMetricsRegistry vector = new VectorMetricsRegistry(store);
        vector.recordVectorWrite();
        ReplicationMetricsRegistry replication =
                new ReplicationMetricsRegistry(new LagTracker());
        replication.recordReplicated();
        replication.recordSuppressed();
        replication.recordConflict();
        MultiModelMetricsRegistry multimodel = new MultiModelMetricsRegistry();
        multimodel.recordJsonWrite();
        multimodel.recordJsonWrite();
        multimodel.recordJsonValidationError();
        multimodel.recordTsWrite();
        multimodel.recordTsWrite();
        multimodel.recordTsWrite();
        multimodel.recordMultiModelBytes(4096);
        BackupMetricsRegistry backup = new BackupMetricsRegistry();
        backup.recordBackup(2048);
        backup.recordRestore(1024);
        backup.setPitrWatermark(99);
        return new ObservabilityRegistry(
                vector, replication, multimodel, backup);
    }
}
