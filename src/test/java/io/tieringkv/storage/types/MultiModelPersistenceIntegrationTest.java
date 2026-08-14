package io.tieringkv.storage.types;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.ReplicationMode;
import io.tieringkv.replication.ReplicationPipeline;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableReader;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模型值持久化闭环（ADR-0320）：WAL / SSTable / 复制对 JSON、时序、
 * 向量类型化值透明且可解码恢复。
 */
class MultiModelPersistenceIntegrationTest {

    @TempDir
    Path dir;

    private static final String JSON = "{\"k\":\"v\"}";

    private static final List<MultiModelCodec.TimePoint> SERIES =
            List.of(new MultiModelCodec.TimePoint(1_000L, 1.5),
                    new MultiModelCodec.TimePoint(2_000L, 2.5));

    private static final float[] VECTOR = {1.0f, -2.0f, 3.5f};

    private static byte[] jsonValue() {
        return MultiModelCodec.encodeJson(JSON);
    }

    private static byte[] seriesValue() {
        return MultiModelCodec.encodeTimeSeries(SERIES);
    }

    private static byte[] vectorValue() {
        return MultiModelCodec.encodeVector(VECTOR);
    }

    @Test
    void walRecoveryPreservesAllModelValues() throws Exception {
        WALConfig config = new WALConfig(dir.resolve("wal"), 1 << 20,
                WALConfig.FsyncPolicy.NO);
        MemTable memTable = MemTable.create();
        try (WALManager wal = new WALManager(config)) {
            StorageEngine storage = new WALStorageEngine(wal, memTable);
            storage.put(b("json"), jsonValue());
            storage.put(b("series"), seriesValue());
            storage.put(b("vector"), vectorValue());
        }

        MemTable recovered = MemTable.create();
        try (WALManager wal = new WALManager(config)) {
            wal.recover(recovered);
        }
        assertThat(MultiModelCodec.decodeJson(
                recovered.get(b("json")))).isEqualTo(JSON);
        assertThat(MultiModelCodec.decodeTimeSeries(
                recovered.get(b("series"))))
                .containsExactlyElementsOf(SERIES);
        assertThat(MultiModelCodec.decodeVector(
                recovered.get(b("vector")))).containsExactly(VECTOR);
    }

    @Test
    void sstableRoundTripPreservesAllModelValues() throws Exception {
        List<KeyValueEntry> entries = new ArrayList<>();
        entries.add(KeyValueEntry.live(b("json"), jsonValue(),
                0, -1, 1));
        entries.add(KeyValueEntry.live(b("series"), seriesValue(),
                1, -1, 1));
        entries.add(KeyValueEntry.live(b("vector"), vectorValue(),
                2, -1, 1));

        SSTableMeta meta;
        try (SSTableWriter writer = new SSTableWriter(
                dir, 1, entries.size(), 10, 4096)) {
            for (KeyValueEntry entry : entries) {
                writer.writeEntry(entry);
            }
            meta = writer.finish();
        }
        try (SSTableReader reader = SSTableReader.open(meta, dir)) {
            assertThat(MultiModelCodec.decodeJson(
                    reader.get(b("json")).value()))
                    .isEqualTo(JSON);
            assertThat(MultiModelCodec.decodeTimeSeries(
                    reader.get(b("series")).value()))
                    .containsExactlyElementsOf(SERIES);
            assertThat(MultiModelCodec.decodeVector(
                    reader.get(b("vector")).value()))
                    .containsExactly(VECTOR);
        }
    }

    @Test
    void replicationDeliversAllModelValues() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(event(1, "json", jsonValue())).join();
        pipeline.replicate(event(2, "series", seriesValue())).join();
        pipeline.replicate(event(3, "vector", vectorValue())).join();
        Thread.yield();
        assertThat(sink.events()).hasSize(3);
        assertThat(MultiModelCodec.decodeJson(
                sink.value("json"))).isEqualTo(JSON);
        assertThat(MultiModelCodec.decodeTimeSeries(
                sink.value("series")))
                .containsExactlyElementsOf(SERIES);
        assertThat(MultiModelCodec.decodeVector(
                sink.value("vector"))).containsExactly(VECTOR);
    }

    private static ChangeEvent event(long seq, String key,
                                     byte[] value) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                b(key), value, false, "t" + seq, "r1", seq);
    }

    private static byte[] b(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 记录收到的值，供断言解码。 */
    private static final class RecordingSink implements ReplicaSink {
        private final String regionId;
        private final List<ChangeEvent> events =
                new CopyOnWriteArrayList<>();

        private RecordingSink(String regionId) {
            this.regionId = regionId;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void>
        apply(ChangeEvent event) {
            events.add(event);
            return java.util.concurrent.CompletableFuture
                    .completedFuture(null);
        }

        @Override
        public String replicaId() {
            return regionId;
        }

        List<ChangeEvent> events() {
            return events;
        }

        byte[] value(String key) {
            return events.stream()
                    .filter(e -> new String(e.key(),
                            StandardCharsets.UTF_8).equals(key))
                    .map(ChangeEvent::value)
                    .findFirst().orElseThrow();
        }
    }
}
