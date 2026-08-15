package io.tieringkv.storage;

import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量索引同步装饰器（ADR-0320 M2 增强）：put 索引 / delete 去索引。 */
class VectorIndexSyncStorageEngineTest {

    private final MemTable memTable = MemTable.create();
    private final VectorIndexStore index = new VectorIndexStore(4);
    private final StorageEngine storage =
            new VectorIndexSyncStorageEngine(memTable, index);
    private final VectorMetricsRegistry metrics =
            new VectorMetricsRegistry(index);
    private final StorageEngine instrumented =
            new VectorIndexSyncStorageEngine(memTable, index, metrics);

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void vectorPutIndexesAndDeleteRemoves() {
        storage.put(bytes("v1"),
                MultiModelCodec.encodeVector(
                        new float[]{1, 0}));
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.store().search(new float[]{1, 0}, 1)
                .get(0).id()).isEqualTo("v1");

        assertThat(storage.delete(bytes("v1"))).isTrue();
        assertThat(index.size()).isZero();
    }

    @Test
    void overwriteRefreshesIndexedVector() {
        storage.put(bytes("v"),
                MultiModelCodec.encodeVector(new float[]{1, 0}));
        storage.put(bytes("v"),
                MultiModelCodec.encodeVector(new float[]{0, 1}));
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.store().search(new float[]{0, 1}, 1)
                .get(0).id()).isEqualTo("v");
    }

    @Test
    void nonVectorValuesAreNotIndexed() {
        storage.put(bytes("s"), bytes("plain"));
        storage.put(bytes("j"),
                MultiModelCodec.encodeJson("{\"a\":1}"));
        storage.put(bytes("t"),
                MultiModelCodec.encodeTimeSeries(java.util.List.of(
                        new MultiModelCodec.TimePoint(1L, 1.0))));
        assertThat(index.size()).isZero();
    }

    @Test
    void deleteMissingVectorDoesNotTouchIndex() {
        storage.put(bytes("v"),
                MultiModelCodec.encodeVector(new float[]{1}));
        assertThat(storage.delete(bytes("absent"))).isFalse();
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void typedValueDecodableAfterStorageRoundTrip() {
        byte[] encoded = MultiModelCodec.encodeVector(
                new float[]{0.5f, -1.0f});
        storage.put(bytes("v"), encoded);
        byte[] read = storage.get(bytes("v"));
        assertThat(TypedValueCodec.typeOf(read))
                .isEqualTo(ValueType.VECTOR);
        assertThat(MultiModelCodec.decodeVector(read))
                .containsExactly(0.5f, -1.0f);
    }

    @Test
    void vectorMetricsRecordedOnPutAndDelete() {
        instrumented.put(bytes("v1"),
                MultiModelCodec.encodeVector(new float[]{1, 0}));
        assertThat(metrics.snapshot().writes()).isEqualTo(1);
        assertThat(metrics.snapshot().vectorCount()).isEqualTo(1);
        assertThat(instrumented.delete(bytes("v1"))).isTrue();
        assertThat(metrics.snapshot().deletes()).isEqualTo(1);
        assertThat(metrics.snapshot().vectorCount()).isZero();
    }

    @Test
    void nonVectorWritesDoNotCountAsVectorMetrics() {
        instrumented.put(bytes("s"), bytes("plain"));
        assertThat(metrics.snapshot().writes()).isZero();
    }
}
