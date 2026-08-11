package io.tieringkv.cdc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** CDC 多消费者组（ADR-0112）：组间进度隔离与独立恢复。 */
class CdcFanOutTest {

    @TempDir
    Path dir;

    @Test
    void twoGroupsIndependentProgress() throws Exception {
        Path logDir = dir.resolve("log");
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 10; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("ckpt"));
        registry.register("warehouse").consume(event -> {
        });
        registry.register("search").consume(event -> {
        });
        assertThat(registry.group("warehouse").checkpoint())
                .isEqualTo(9);
        assertThat(registry.group("search").checkpoint())
                .isEqualTo(9);
    }

    @Test
    void groupCrashDoesNotAffectOther() throws Exception {
        Path logDir = dir.resolve("crash-log");
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 10; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("crash-ckpt"));
        registry.register("healthy").consume(event -> {
        });
        // 新事件只被 healthy 消费；另一个组随后注册从 0 开始独立消费
        producer.emit(ChangeEvent.EventType.PUT, bytes("k10"),
                bytes("v10"), false, "t10", "r1");
        registry.register("late").consume(event -> {
        });
        assertThat(registry.group("late").checkpoint()).isEqualTo(10);
        assertThat(registry.group("healthy").checkpoint()).isEqualTo(9);
    }

    @Test
    void registryListAndUnregister() throws Exception {
        Path logDir = dir.resolve("list-log");
        new CDCProducer(logDir);
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("list-ckpt"));
        registry.register("a");
        registry.register("b");
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.groupIds()).containsExactlyInAnyOrder("a", "b");
        assertThat(registry.unregister("a")).isTrue();
        assertThat(registry.unregister("missing")).isFalse();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void groupRecoversFromOwnCheckpoint() throws Exception {
        Path logDir = dir.resolve("recover-log");
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 20; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumerRegistry first = new CDCConsumerRegistry(
                logDir, dir.resolve("recover-ckpt"));
        first.register("g1").consume(event -> {
        });
        CDCConsumerRegistry restarted = new CDCConsumerRegistry(
                logDir, dir.resolve("recover-ckpt"));
        List<ChangeEvent> reapplied = new CopyOnWriteArrayList<>();
        restarted.register("g1").consume(reapplied::add);
        assertThat(reapplied).isEmpty();
        assertThat(restarted.group("g1").checkpoint()).isEqualTo(19);
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedFanOutCounts(int count) throws Exception {
        Path logDir = dir.resolve("fan-" + count);
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < count; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("fan-ckpt-" + count));
        registry.register("g1").consume(event -> {
        });
        registry.register("g2").consume(event -> {
        });
        assertThat(registry.group("g1").checkpoint())
                .isEqualTo(count - 1);
        assertThat(registry.group("g2").checkpoint())
                .isEqualTo(count - 1);
    }

    @Test
    void groupDeliversDistinctEventTypes() throws Exception {
        Path logDir = dir.resolve("types-log");
        CDCProducer producer = new CDCProducer(logDir);
        producer.emit(ChangeEvent.EventType.DELETE, bytes("k"), null,
                true, "t1", "r1");
        producer.emit(ChangeEvent.EventType.TXN_COMMIT, bytes("k2"),
                bytes("v2"), false, "t2", "r1");
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("types-ckpt"));
        List<ChangeEvent.EventType> types = new CopyOnWriteArrayList<>();
        registry.register("g1").consume(event -> types.add(event.type()));
        assertThat(types).containsExactly(ChangeEvent.EventType.DELETE,
                ChangeEvent.EventType.TXN_COMMIT);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
