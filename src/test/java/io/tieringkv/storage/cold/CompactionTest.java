package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionTest {

    @TempDir
    Path dir;

    @Test
    void latestWinsAcrossTables() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir));
        cold.writeTable(entries("k1", "v1-old", "k2", "v2-old"));
        cold.writeTable(entries("k1", "v1-new", "k3", "v3"));

        assertThat(cold.get("k1".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v1-new".getBytes(StandardCharsets.UTF_8));
        assertThat(cold.get("k2".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v2-old".getBytes(StandardCharsets.UTF_8));

        cold.compactAll();
        assertThat(cold.tablesSnapshot()).hasSize(1);
        assertThat(cold.get("k1".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v1-new".getBytes(StandardCharsets.UTF_8));
        assertThat(cold.get("k2".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v2-old".getBytes(StandardCharsets.UTF_8));
        assertThat(cold.get("k3".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v3".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void tombstoneRemovesKeyAfterCompaction() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir));
        byte[] key = "k1".getBytes(StandardCharsets.UTF_8);
        cold.writeTable(entries("k1", "v1", "k2", "v2"));
        cold.writeTable(List.of(KeyValueEntry.tombstone(key, 0, 99)));

        assertThat(cold.get(key)).isNull();
        cold.compactAll();
        assertThat(cold.get(key)).isNull();
        assertThat(cold.get("k2".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v2".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void expiredTtlIsDroppedByCompaction() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir));
        KeyValueEntry expired = KeyValueEntry.live(
                "expired".getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, 50, 1);
        KeyValueEntry live = KeyValueEntry.live(
                "live".getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, 2);
        cold.writeTable(List.of(expired, live));

        cold.compactAll();
        assertThat(cold.get("expired".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(cold.get("live".getBytes(StandardCharsets.UTF_8))).isNotNull();
    }

    @Test
    void autoCompactionTriggersAtThreshold() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, 1 << 20, 4));
        for (int i = 0; i < 5; i++) {
            cold.writeTable(entries("k" + i, "v" + i));
        }
        // 第 4 张表触发合并 → 5 次写入后表数应明显少于 5
        assertThat(cold.tablesSnapshot()).hasSizeLessThan(5);
        for (int i = 0; i < 5; i++) {
            assertThat(cold.get(("k" + i).getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

    private static List<KeyValueEntry> entries(String... keyValues) {
        List<KeyValueEntry> result = new ArrayList<>();
        long version = 1;
        for (int i = 0; i < keyValues.length; i += 2) {
            result.add(KeyValueEntry.live(
                    keyValues[i].getBytes(StandardCharsets.UTF_8),
                    keyValues[i + 1].getBytes(StandardCharsets.UTF_8), 0, -1, version++));
        }
        return result;
    }
}
