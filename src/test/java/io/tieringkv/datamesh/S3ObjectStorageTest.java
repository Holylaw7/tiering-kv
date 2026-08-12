package io.tieringkv.datamesh;

import io.tieringkv.datamesh.S3ObjectStorage.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S3 兼容存储（ADR-0200）：put/get/delete + fallback。 */
class S3ObjectStorageTest {

    @Test
    void simulatedFallbackWhenNoEndpoint() {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                "");
        assertThat(storage.realEndpointConfigured()).isFalse();
        S3Object object = storage.put("obj-1", bytes("data"), 1);
        assertThat(object.bucket()).isEqualTo("tiering");
        assertThat(storage.get("obj-1").orElseThrow().data())
                .isEqualTo(bytes("data"));
    }

    @Test
    void realEndpointConfigured() {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                "https://s3.example.com");
        assertThat(storage.realEndpointConfigured()).isTrue();
        assertThat(storage.endpoint())
                .isEqualTo("https://s3.example.com");
    }

    @Test
    void deleteRemovesObject() {
        S3ObjectStorage storage = storage();
        storage.put("obj-1", bytes("d"), 1);
        assertThat(storage.delete("obj-1")).isTrue();
        assertThat(storage.delete("obj-1")).isFalse();
        assertThat(storage.get("obj-1")).isEmpty();
    }

    @Test
    void missingObjectEmpty() {
        assertThat(storage().get("missing")).isEmpty();
    }

    @Test
    void blankBucketRejected() {
        assertThatThrownBy(() -> new S3ObjectStorage("", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDataRejected() {
        assertThatThrownBy(() -> storage().put("k", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dataCloned() {
        S3ObjectStorage storage = storage();
        byte[] data = bytes("data");
        storage.put("obj-1", data, 1);
        data[0] = 'X';
        assertThat(storage.get("obj-1").orElseThrow().data()[0])
                .isEqualTo((byte) 'd');
    }

    @Test
    void keysAndSizeTracked() {
        S3ObjectStorage storage = storage();
        storage.put("a", bytes("1"), 1);
        storage.put("b", bytes("2"), 2);
        assertThat(storage.keys()).containsExactlyInAnyOrder(
                "a", "b");
        assertThat(storage.size()).isEqualTo(2);
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"a", "obj-1", "path/to/key",
            "with space", "unicode-键"})
    void parameterizedKeys(String key) {
        S3ObjectStorage storage = storage();
        storage.put(key, bytes("d"), 1);
        assertThat(storage.get(key)).isPresent();
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {1, 100, 4096})
    void parameterizedDataSizes(int size) {
        S3ObjectStorage storage = storage();
        byte[] data = new byte[size];
        storage.put("obj-1", data, 1);
        assertThat(storage.get("obj-1").orElseThrow().data())
                .hasSize(size);
    }

    @Test
    void overwriteReplaces() {
        S3ObjectStorage storage = storage();
        storage.put("obj-1", bytes("old"), 1);
        storage.put("obj-1", bytes("new"), 2);
        assertThat(storage.get("obj-1").orElseThrow().data())
                .isEqualTo(bytes("new"));
        assertThat(storage.size()).isEqualTo(1);
    }

    @Test
    void concurrentPutStable() throws Exception {
        S3ObjectStorage storage = storage();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    storage.put("obj-" + i, bytes("d"), i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(storage.size()).isEqualTo(100);
    }

    private static S3ObjectStorage storage() {
        return new S3ObjectStorage("tiering", "");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
