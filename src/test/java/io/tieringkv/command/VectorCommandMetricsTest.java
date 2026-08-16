package io.tieringkv.command;

import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量 checkpoint 喂数（ADR-0344 收口）：VECTOR.CHECKPOINT → 水位。 */
class VectorCommandMetricsTest {

    @TempDir
    Path dir;

    @Test
    void checkpointFeedsVectorWatermark() {
        VectorIndexStore store = new VectorIndexStore(4);
        VectorCollectionRegistry collections =
                VectorCollectionRegistry.ofDefault(store);
        collections.configureCheckpoint(dir.resolve("checkpoints"));
        VectorMetricsRegistry metrics = new VectorMetricsRegistry(store);
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefaultWithVectorAndMetrics(
                        () -> "info", Map.of(), collections, null, metrics),
                MemTable.create());

        execute(engine, "vector.add", "v1", "2", "1.0", "0.0");
        execute(engine, "vector.add", "v2", "2", "0.0", "1.0");
        execute(engine, "vector.checkpoint");

        VectorMetricsRegistry.Snapshot s = metrics.snapshot();
        assertThat(s.checkpoints()).isEqualTo(1);
        assertThat(s.checkpointWatermark()).isEqualTo(2);
        assertThat(metrics.metricLines())
                .contains("vector_checkpoints:1")
                .contains("vector_checkpoint_watermark:2");
    }

    private static void execute(CommandEngine engine, String name,
                                String... args) {
        engine.execute(new RespCommand(name,
                List.of(args).stream()
                        .map(s -> s.getBytes(StandardCharsets.UTF_8))
                        .toList()));
    }
}
