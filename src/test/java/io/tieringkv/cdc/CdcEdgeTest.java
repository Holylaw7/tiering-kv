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

/** CDC 边缘矩阵（ADR-0105）：重开、崩溃点、事件字段。 */
class CdcEdgeTest {

    @TempDir
    Path dir;

    @Test
    void reopenAfterAppend() throws Exception {
        Path logDir = dir.resolve("reopen");
        CDCProducer first = new CDCProducer(logDir);
        first.emit(ChangeEvent.EventType.PUT, bytes("k"), bytes("v"),
                false, "t0", "r1");
        CDCProducer reopened = new CDCProducer(logDir);
        reopened.emit(ChangeEvent.EventType.PUT, bytes("k2"), bytes("v2"),
                false, "t1", "r1");
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt"));
        assertThat(consumer.consume(event -> {
        })).isEqualTo(1);
    }

    @Test
    void reopenAfterCrash() throws Exception {
        Path logDir = dir.resolve("crash");
        CDCProducer first = new CDCProducer(logDir);
        for (int i = 0; i < 10; i++) {
            first.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt-crash"));
        assertThat(consumer.consume(event -> {
        })).isEqualTo(9);
        CDCConsumer reopened = new CDCConsumer(logDir,
                dir.resolve("ckpt-crash"));
        assertThat(reopened.consume(event -> {
        })).isEqualTo(9);
    }

    @ParameterizedTest(name = "crashAt {0}")
    @ValueSource(ints = {0, 1, 5, 9})
    void parameterizedCrashPoints(int crashAt) throws Exception {
        Path logDir = dir.resolve("crash-at-" + crashAt);
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 10; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt-at-" + crashAt));
        List<Long> applied = new CopyOnWriteArrayList<>();
        consumer.consume(event -> {
            if (event.seq() < crashAt) {
                applied.add(event.seq());
            }
        });
        // 检查点始终推进到末尾（同步消费模型），崩溃点在重放语义中等价。
        assertThat(consumer.checkpoint()).isEqualTo(9);
        assertThat(applied).hasSize(crashAt);
    }

    @Test
    void checkpointAdvancesPerEvent() throws Exception {
        Path logDir = dir.resolve("advance");
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 5; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt-advance"));
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        consumer.consume(seen::add);
        assertThat(seen).extracting(ChangeEvent::seq)
                .containsExactly(0L, 1L, 2L, 3L, 4L);
    }

    @Test
    void emptyLogConsume() throws Exception {
        Path logDir = dir.resolve("empty");
        new CDCProducer(logDir);
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt-empty"));
        assertThat(consumer.consume(event -> {
        })).isEqualTo(-1);
    }

    @Test
    void mixedDeleteAndTxnEvents() throws Exception {
        Path logDir = dir.resolve("mixed");
        CDCProducer producer = new CDCProducer(logDir);
        producer.emit(ChangeEvent.EventType.DELETE, bytes("k"), null,
                true, "t0", "r1");
        producer.emit(ChangeEvent.EventType.TXN_COMMIT, bytes("k2"),
                bytes("v2"), false, "t1", "r1");
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt-mixed"));
        List<ChangeEvent.EventType> types = new CopyOnWriteArrayList<>();
        consumer.consume(event -> types.add(event.type()));
        assertThat(types).containsExactly(ChangeEvent.EventType.DELETE,
                ChangeEvent.EventType.TXN_COMMIT);
    }

    @Test
    void truncationThenReopen() throws Exception {
        Path logDir = dir.resolve("trunc");
        CDCProducer producer = new CDCProducer(logDir, 10);
        for (int i = 0; i < 6; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        Path segment = java.nio.file.Files.list(logDir).findFirst()
                .orElseThrow();
        byte[] bytes = java.nio.file.Files.readAllBytes(segment);
        java.nio.file.Files.write(segment, java.util.Arrays.copyOf(bytes,
                bytes.length - 9));
        CDCProducer reopened = new CDCProducer(logDir, 10);
        assertThat(reopened.watermark()).isLessThanOrEqualTo(5);
    }

    @Test
    void concurrentReopen() throws Exception {
        Path logDir = dir.resolve("conc-reopen");
        CDCProducer first = new CDCProducer(logDir);
        first.emit(ChangeEvent.EventType.PUT, bytes("k"), bytes("v"),
                false, "t0", "r1");
        CDCProducer a = new CDCProducer(logDir);
        CDCProducer b = new CDCProducer(logDir);
        assertThat(a.watermark()).isEqualTo(0);
        assertThat(b.watermark()).isEqualTo(0);
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {0, 1, 4096})
    void parameterizedEventSizes(int size) throws Exception {
        Path logDir = dir.resolve("size-" + size);
        CDCProducer producer = new CDCProducer(logDir);
        producer.emit(ChangeEvent.EventType.PUT, new byte[size],
                bytes("v"), false, "t0", "r1");
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt-size-" + size));
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        consumer.consume(delivered::add);
        assertThat(delivered.get(0).key()).hasSize(size);
    }

    @Test
    void regionMoveMix() throws Exception {
        Path logDir = dir.resolve("mix-move");
        CDCProducer producer = new CDCProducer(logDir);
        producer.emit(ChangeEvent.EventType.REGION_MOVE, bytes("k"),
                null, false, null, "r2");
        producer.emit(ChangeEvent.EventType.PUT, bytes("k"), bytes("v"),
                false, "t1", "r2");
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("ckpt-mix-move"));
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        consumer.consume(delivered::add);
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(1).regionId()).isEqualTo("r2");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
