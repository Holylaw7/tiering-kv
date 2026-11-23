package io.tieringkv.command;

import io.tieringkv.observability.MultiModelMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 多模型命令喂数（ADR-0345）：JSON/TS 写与字节计数。 */
class MultiModelCommandMetricsTest {

    private final MultiModelMetricsRegistry metrics =
            new MultiModelMetricsRegistry();
    private final CommandEngine engine = new CommandEngine(
            CommandRegistry.createDefaultWithVectorAndMetrics(
                    () -> "info", Map.of(),
                    VectorCollectionRegistry.ofDefault(
                            new VectorIndexStore(4)),
                    metrics),
            MemTable.create());

    @Test
    void jsonSetFeedsWriteAndBytes() {
        execute("json.set", "k", "{\"a\":1}");
        assertThat(metrics.snapshot().jsonWrites()).isEqualTo(1);
        assertThat(metrics.snapshot().multimodelBytes()).isPositive();
    }

    @Test
    void invalidJsonFeedsValidationErrorOnly() {
        execute("json.set", "k", "{\"bad\"");
        assertThat(metrics.snapshot().jsonValidationErrors())
                .isEqualTo(1);
        assertThat(metrics.snapshot().jsonWrites()).isZero();
    }

    @Test
    void tsAddAndIncrByFeedTsWrites() {
        execute("ts.add", "k", "1", "1.0");
        execute("ts.incrby", "k", "2.0");
        assertThat(metrics.snapshot().tsWrites()).isEqualTo(2);
        assertThat(metrics.snapshot().multimodelBytes()).isPositive();
    }

    private void execute(String name, String... args) {
        engine.execute(new RespCommand(name,
                List.of(args).stream()
                        .map(s -> s.getBytes(StandardCharsets.UTF_8))
                        .toList()));
    }
}
