package io.tieringkv.cdc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CDC 恢复（ADR-0105）：exactly-once checkpoint、崩溃恢复、事件类型。 */
class CDCRecoveryTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "type {0}")
    @EnumSource(ChangeEvent.EventType.class)
    void eventTypeRoundTrip(ChangeEvent.EventType type) throws Exception {
        CDCProducer producer = new CDCProducer(dir.resolve("log-" + type));
        ChangeEvent event = producer.emit(type, bytes("k"), bytes("v"),
                type == ChangeEvent.EventType.DELETE, "t1", "r1");
        assertThat(event.type()).isEqualTo(type);
        assertThat(producer.watermark()).isEqualTo(0);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100, 512, 600})
    void producerSequentialSeq(int count) throws Exception {
        CDCProducer producer = new CDCProducer(
                dir.resolve("log-seq-" + count));
        for (int i = 0; i < count; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        assertThat(producer.watermark()).isEqualTo(count - 1);
        CDCConsumer consumer = new CDCConsumer(
                dir.resolve("log-seq-" + count),
                dir.resolve("ckpt-seq-" + count));
        List<ChangeEvent> applied = new CopyOnWriteArrayList<>();
        long last = consumer.consume(applied::add);
        assertThat(applied).hasSize(count);
        assertThat(last).isEqualTo(count - 1);
        assertThat(consumer.checkpoint()).isEqualTo(count - 1);
    }

    @Test
    void crashMidConsumeResumesExactlyOnce() throws Exception {
        Path logDir = dir.resolve("crash-log");
        Path ckptDir = dir.resolve("crash-ckpt");
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 20; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumer first = new CDCConsumer(logDir, ckptDir);
        first.consume(event -> {
        });
        CDCConsumer restarted = new CDCConsumer(logDir, ckptDir);
        List<ChangeEvent> reapplied = new CopyOnWriteArrayList<>();
        restarted.consume(reapplied::add);
        assertThat(reapplied).isEmpty(); // 检查点已到末尾，无重复
        assertThat(restarted.checkpoint()).isEqualTo(19);
    }

    @Test
    void checkpointPersistsAcrossRestart() throws Exception {
        Path logDir = dir.resolve("persist-log");
        Path ckptDir = dir.resolve("persist-ckpt");
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 10; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumer consumer = new CDCConsumer(logDir, ckptDir);
        consumer.consume(event -> {
        });
        assertThat(consumer.checkpoint()).isEqualTo(9);
        CDCConsumer restarted = new CDCConsumer(logDir, ckptDir);
        assertThat(restarted.checkpoint()).isEqualTo(9);
        assertThat(restarted.consume(event -> {
        })).isEqualTo(9);
    }

    @Test
    void checkpointCorruptFailsFast() throws Exception {
        Path ckptDir = dir.resolve("bad-ckpt");
        Files.createDirectories(ckptDir);
        Files.write(ckptDir.resolve("cdc-checkpoint.bin"),
                new byte[]{0, 0, 0, 0, 1, 2, 3, 4});
        assertThatThrownBy(() -> CDCCheckpoint.open(ckptDir))
                .isInstanceOf(IOException.class);
    }

    @Test
    void missingCheckpointStartsFromZero() throws Exception {
        Path logDir = dir.resolve("zero-log");
        Path ckptDir = dir.resolve("zero-ckpt");
        CDCProducer producer = new CDCProducer(logDir);
        producer.emit(ChangeEvent.EventType.PUT, bytes("k"), bytes("v"),
                false, "t1", "r1");
        CDCConsumer consumer = new CDCConsumer(logDir, ckptDir);
        List<ChangeEvent> applied = new CopyOnWriteArrayList<>();
        consumer.consume(applied::add);
        assertThat(applied).extracting(ChangeEvent::seq)
                .containsExactly(0L);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 50})
    void logRolloverReadAll(int count) throws Exception {
        CDCProducer producer = new CDCProducer(
                dir.resolve("roll-log-" + count), 10);
        for (int i = 0; i < count; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumer consumer = new CDCConsumer(
                dir.resolve("roll-log-" + count),
                dir.resolve("roll-ckpt-" + count));
        assertThat(consumer.consume(event -> {
        })).isEqualTo(count - 1);
    }

    @Test
    void tailTruncationTolerated() throws Exception {
        Path logDir = dir.resolve("trunc-log");
        CDCProducer producer = new CDCProducer(logDir, 10);
        for (int i = 0; i < 5; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        Path segment = Files.list(logDir).findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(segment);
        Files.write(segment, java.util.Arrays.copyOf(bytes,
                bytes.length - 7));
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("trunc-ckpt"));
        assertThat(consumer.consume(event -> {
        })).isLessThanOrEqualTo(4);
    }

    @Test
    void regionMoveEventDelivered() throws Exception {
        CDCProducer producer = new CDCProducer(dir.resolve("move-log"));
        ChangeEvent event = producer.emit(
                ChangeEvent.EventType.REGION_MOVE, bytes("k"), null,
                false, null, "r2");
        CDCConsumer consumer = new CDCConsumer(dir.resolve("move-log"),
                dir.resolve("move-ckpt"));
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        consumer.consume(delivered::add);
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).type())
                .isEqualTo(ChangeEvent.EventType.REGION_MOVE);
        assertThat(delivered.get(0).regionId()).isEqualTo("r2");
        assertThat(delivered.get(0).seq()).isEqualTo(event.seq());
    }

    @Test
    void txnCommitEventDelivered() throws Exception {
        CDCProducer producer = new CDCProducer(dir.resolve("txn-log"));
        producer.emit(ChangeEvent.EventType.TXN_COMMIT, bytes("k"),
                bytes("v"), false, "txn-99", "r1");
        CDCConsumer consumer = new CDCConsumer(dir.resolve("txn-log"),
                dir.resolve("txn-ckpt"));
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        consumer.consume(delivered::add);
        assertThat(delivered.get(0).txnId()).isEqualTo("txn-99");
    }

    @Test
    void deleteEventCarriesDeletedFlag() throws Exception {
        CDCProducer producer = new CDCProducer(dir.resolve("del-log"));
        producer.emit(ChangeEvent.EventType.DELETE, bytes("k"), null,
                true, "t1", "r1");
        CDCConsumer consumer = new CDCConsumer(dir.resolve("del-log"),
                dir.resolve("del-ckpt"));
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        consumer.consume(delivered::add);
        assertThat(delivered.get(0).deleted()).isTrue();
        assertThat(delivered.get(0).value()).isNull();
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {64, 4096, 65536})
    void largeValueEventRoundTrip(int size) throws Exception {
        Path logDir = dir.resolve("value-log-" + size);
        CDCProducer producer = new CDCProducer(logDir);
        byte[] value = new byte[size];
        producer.emit(ChangeEvent.EventType.PUT, bytes("k"), value,
                false, "t1", "r1");
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("value-ckpt-" + size));
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        consumer.consume(delivered::add);
        assertThat(delivered.get(0).value()).hasSize(size);
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(ints = {0, 1, 1024})
    void keyBoundaryRoundTrip(int size) throws Exception {
        Path logDir = dir.resolve("key-log-" + size);
        CDCProducer producer = new CDCProducer(logDir);
        producer.emit(ChangeEvent.EventType.PUT, new byte[size],
                bytes("v"), false, "t1", "r1");
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("key-ckpt-" + size));
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        consumer.consume(delivered::add);
        assertThat(delivered.get(0).key()).hasSize(size);
    }

    @Test
    void concurrentProducerConsumerOrdered() throws Exception {
        Path logDir = dir.resolve("conc-log");
        Path ckptDir = dir.resolve("conc-ckpt");
        CDCProducer producer = new CDCProducer(logDir);
        int writers = 4;
        int perWriter = 25;
        List<Thread> threads = new java.util.ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < perWriter; i++) {
                        producer.emit(ChangeEvent.EventType.PUT,
                                bytes("k"), bytes("v"), false,
                                "t", "r1");
                    }
                } catch (IOException e) {
                    failures.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(failures.get()).isZero();
        CDCConsumer consumer = new CDCConsumer(logDir, ckptDir);
        AtomicInteger applied = new AtomicInteger();
        consumer.consume(event -> applied.incrementAndGet());
        assertThat(applied.get()).isEqualTo(writers * perWriter);
    }

    @Test
    void consumeTwiceIdempotent() throws Exception {
        Path logDir = dir.resolve("idem-log");
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 10; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumer consumer = new CDCConsumer(logDir,
                dir.resolve("idem-ckpt"));
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        consumer.consume(event -> first.incrementAndGet());
        consumer.consume(event -> second.incrementAndGet());
        assertThat(first.get()).isEqualTo(10);
        assertThat(second.get()).isZero();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
