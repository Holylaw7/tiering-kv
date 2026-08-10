package io.tieringkv.storage.cold;

import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColdStorageEngineTest {

    @TempDir
    Path dir;

    @Test
    void putGetDeleteWithPending() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir));
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        cold.put(KeyValueEntry.live(key, "v".getBytes(), 0, -1, 1));
        assertThat(cold.get(key)).isEqualTo("v".getBytes(StandardCharsets.UTF_8));
        cold.delete(key);
        assertThat(cold.get(key)).isNull();
    }

    @Test
    void pendingFlushesToMultipleTables() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, 1, 100)); // 阈值 1：每次 put 立即落表
        for (int i = 0; i < 10; i++) {
            cold.put(KeyValueEntry.live(
                    ("k" + i).getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, i));
        }
        assertThat(cold.tablesSnapshot()).hasSize(10);
        for (int i = 0; i < 10; i++) {
            assertThat(cold.get(("k" + i).getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

    @Test
    void restartLoadsManifest() throws Exception {
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(
                dir, 4096, 10, 1, 100);
        try (ColdStorageEngine cold = new ColdStorageEngine(config)) {
            for (int i = 0; i < 5; i++) {
                cold.put(KeyValueEntry.live(
                        ("k" + i).getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, i));
            }
        }
        try (ColdStorageEngine restarted = new ColdStorageEngine(config)) {
            assertThat(restarted.tablesSnapshot()).hasSize(5);
            assertThat(restarted.get("k3".getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

    @Test
    void iteratorMergesPendingAndTables() throws Exception {
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(
                dir, 4096, 10, 1 << 20, 100);
        try (ColdStorageEngine cold = new ColdStorageEngine(config)) {
            cold.writeTable(entries("a", "b", "c"));
            cold.put(KeyValueEntry.live(
                    "d".getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, 1));
            cold.put(KeyValueEntry.live(
                    "e".getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, 2));
            List<String> keys = new ArrayList<>();
            try (StorageIterator iterator = cold.iterator()) {
                while (iterator.hasNext()) {
                    keys.add(new String(iterator.next().key(), StandardCharsets.UTF_8));
                }
            }
            assertThat(keys).containsExactly("a", "b", "c", "d", "e");
            assertThat(cold.get("d".getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

    private static List<KeyValueEntry> entries(String... keys) {
        List<KeyValueEntry> result = new ArrayList<>();
        long version = 1;
        for (String key : keys) {
            result.add(KeyValueEntry.live(
                    key.getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, version++));
        }
        return result;
    }
}
