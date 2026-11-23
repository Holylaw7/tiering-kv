package io.tieringkv.command;

import io.tieringkv.observability.BackupMetricsRegistry;
import io.tieringkv.observability.MultiModelMetricsRegistry;
import io.tieringkv.observability.ObservabilityRegistry;
import io.tieringkv.observability.ReplicationMetricsRegistry;
import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.replication.LagTracker;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** INFO sections 接线（ADR-0344）：vector/replication/multimodel/backup。 */
class InfoCommandSectionsTest {

    @Test
    void infoSectionsReturnAggregatedText() {
        VectorMetricsRegistry vector = new VectorMetricsRegistry(
                new VectorIndexStore(4));
        ReplicationMetricsRegistry replication =
                new ReplicationMetricsRegistry(new LagTracker());
        MultiModelMetricsRegistry multimodel = new MultiModelMetricsRegistry();
        multimodel.recordTsWrite();
        BackupMetricsRegistry backup = new BackupMetricsRegistry();
        backup.recordBackup(1024);
        ObservabilityRegistry observability = new ObservabilityRegistry(
                vector, replication, multimodel, backup);

        CommandRegistry registry = CommandRegistry.createDefaultWithVector(
                () -> "info", observability.infoSections(),
                new VectorIndexStore(4));
        CommandEngine engine = new CommandEngine(
                registry, MemTable.create());

        assertThat(info(engine, "vector")).contains("# Vector")
                .contains("vector_count:0");
        assertThat(info(engine, "replication")).contains("# Replication")
                .contains("replication_replicas:0");
        assertThat(info(engine, "multimodel")).contains("# MultiModel")
                .contains("multimodel_ts_writes:1");
        assertThat(info(engine, "backup")).contains("# Backup")
                .contains("backup_total:1");
    }

    private static String info(CommandEngine engine, String section) {
        RespValue value = engine.execute(new RespCommand(
                "info", List.of(section.getBytes(StandardCharsets.UTF_8))));
        assertThat(value).isInstanceOf(RespBulkString.class);
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }
}
