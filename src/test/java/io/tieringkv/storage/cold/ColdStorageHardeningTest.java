package io.tieringkv.storage.cold;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColdStorageHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void compactLeveledPreservesUpperLevelTables() throws Exception {
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(tempDir, 4096, 10.0, 1 << 20, 10);
        try (ColdStorageEngine engine = new ColdStorageEngine(config)) {
            LeveledCompaction lc = new LeveledCompaction(2, 2);

            // Push one table to L2 so it sits outside the L0->L1 compaction scope
            SSTableMeta l2 = engine.writeTable(List.of(live("k-l2", "v-l2", 1L)));
            lc.onTableWritten(l2);
            lc.promote(0, l2);
            lc.promote(1, l2);

            // Populate L0 beyond max L0 tables (2) to trigger L0 -> L1 merge
            SSTableMeta t1 = engine.writeTable(List.of(live("k-a", "v-a", 2L)));
            SSTableMeta t2 = engine.writeTable(List.of(live("k-b", "v-b", 3L)));
            SSTableMeta t3 = engine.writeTable(List.of(live("k-c", "v-c", 4L)));
            lc.onTableWritten(t1);
            lc.onTableWritten(t2);
            lc.onTableWritten(t3);

            SSTableMeta compacted = engine.compactLeveled(lc);
            assertThat(compacted).isNotNull();

            // L2 data and metadata must still exist after L0->L1 compaction
            assertThat(engine.get(bytes("k-l2"))).isEqualTo(bytes("v-l2"));
            assertThat(engine.get(bytes("k-a"))).isEqualTo(bytes("v-a"));
            assertThat(engine.get(bytes("k-b"))).isEqualTo(bytes("v-b"));
            assertThat(engine.get(bytes("k-c"))).isEqualTo(bytes("v-c"));
            assertThat(engine.tablesSnapshot()).hasSize(2);
            assertThat(engine.tablesSnapshot()).extracting(SSTableMeta::id)
                    .containsExactlyInAnyOrder(l2.id(), compacted.id());
        }
    }

    @Test
    void iteratorPinPreservesActiveScanDuringCompaction() throws Exception {
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(tempDir, 4096, 10.0, 1 << 20, 10);
        try (ColdStorageEngine engine = new ColdStorageEngine(config)) {
            engine.writeTable(List.of(live("k1", "v1", 1L)));
            engine.writeTable(List.of(live("k2", "v2", 2L)));

            List<String> keys = new ArrayList<>();
            try (StorageIterator it = engine.iterator()) {
                assertThat(it.hasNext()).isTrue();
                keys.add(new String(it.next().key(), StandardCharsets.UTF_8));

                // Compact while iterator is open in the middle of scanning
                SSTableMeta merged = engine.compactAll();
                assertThat(merged).isNotNull();

                while (it.hasNext()) {
                    keys.add(new String(it.next().key(), StandardCharsets.UTF_8));
                }
            }
            assertThat(keys).containsExactlyInAnyOrder("k1", "k2");
            assertThat(engine.tablesSnapshot()).hasSize(1);
        }
    }

    @Test
    void compactAllTombstonesDoesNotCrashManifest() throws Exception {
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(tempDir, 4096, 10.0, 1 << 20, 10);
        try (ColdStorageEngine engine = new ColdStorageEngine(config)) {
            long now = System.currentTimeMillis();
            engine.writeTable(List.of(KeyValueEntry.tombstone(bytes("t1"), now, 1L)));
            engine.writeTable(List.of(KeyValueEntry.tombstone(bytes("t2"), now, 2L)));

            SSTableMeta compacted = engine.compactAll();
            assertThat(compacted).isNotNull();
            assertThat(engine.get(bytes("t1"))).isNull();
            assertThat(engine.get(bytes("t2"))).isNull();
        }
    }

    private static KeyValueEntry live(String key, String value, long version) {
        return KeyValueEntry.live(bytes(key), bytes(value), System.currentTimeMillis(), StorageEngine.NO_TTL, version);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
