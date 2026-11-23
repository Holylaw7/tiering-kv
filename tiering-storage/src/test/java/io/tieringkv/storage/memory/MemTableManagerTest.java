package io.tieringkv.storage.memory;

import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Active/Immutable MemTable 轮转（ADR-0324）：写入不停顿 + 读合并 +
 * flush + WAL 恢复。 */
class MemTableManagerTest {

    @TempDir
    Path dir;

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static WALManager wal(Path dir) throws Exception {
        return new WALManager(new WALConfig(dir, 1 << 20,
                WALConfig.FsyncPolicy.NO));
    }

    @Test
    void writesAlwaysHitActive() throws Exception {
        try (WALManager wal = wal(dir.resolve("wal"))) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            manager.put(bytes("k1"), bytes("v1"));
            assertThat(manager.active().size()).isEqualTo(1);
            assertThat(manager.immutableCount()).isZero();
            manager.rotate();
            manager.put(bytes("k2"), bytes("v2"));
            assertThat(manager.immutableCount()).isEqualTo(1);
            assertThat(manager.active().get(bytes("k2")))
                    .isEqualTo(bytes("v2"));
        }
    }

    @Test
    void readMergesAcrossTablesNewestWins() throws Exception {
        try (WALManager wal = wal(dir.resolve("wal2"))) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            manager.put(bytes("k"), bytes("v1"));
            manager.put(bytes("a"), bytes("va"));
            manager.rotate();
            manager.put(bytes("k"), bytes("v2"));
            assertThat(manager.get(bytes("k")))
                    .isEqualTo(bytes("v2"));
            assertThat(manager.get(bytes("a")))
                    .isEqualTo(bytes("va"));
            assertThat(manager.exists(bytes("k"))).isTrue();
        }
    }

    @Test
    void deleteOverridesOlderTable() throws Exception {
        try (WALManager wal = wal(dir.resolve("wal3"))) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            manager.put(bytes("k"), bytes("v"));
            manager.rotate();
            assertThat(manager.delete(bytes("k"))).isTrue();
            assertThat(manager.get(bytes("k"))).isNull();
        }
    }

    @Test
    void flushOldestMovesToColdAndRemovesImmutable() throws Exception {
        try (WALManager wal = wal(dir.resolve("wal4"));
             ColdStorageEngine cold = new ColdStorageEngine(
                     ColdStorageEngine.Config.defaults(
                             dir.resolve("cold")))) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            manager.put(bytes("k1"), bytes("v1"));
            manager.rotate();
            manager.put(bytes("k2"), bytes("v2"));

            assertThat(manager.flushOldest(cold)).isPresent();
            assertThat(manager.immutableCount()).isZero();
            assertThat(cold.get(bytes("k1"))).isEqualTo(bytes("v1"));
            assertThat(manager.get(bytes("k2"))).isEqualTo(bytes("v2"));
            assertThat(manager.flushOldest(cold)).isEmpty();
        }
    }

    @Test
    void iteratorMergesOrderedWithNewestWins() throws Exception {
        try (WALManager wal = wal(dir.resolve("wal5"))) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            manager.put(bytes("b"), bytes("b-old"));
            manager.put(bytes("a"), bytes("a1"));
            manager.rotate();
            manager.put(bytes("b"), bytes("b-new"));
            manager.put(bytes("c"), bytes("c1"));

            List<String> keys = new ArrayList<>();
            try (StorageIterator iterator = manager.iterator()) {
                while (iterator.hasNext()) {
                    keys.add(new String(iterator.next().key(),
                            StandardCharsets.UTF_8));
                }
            }
            assertThat(keys).containsExactly("a", "b", "c");
            assertThat(manager.iterator()).isNotNull();
        }
    }

    @Test
    void walRecoveryRebuildsAllWrites() throws Exception {
        Path walDir = dir.resolve("wal6");
        try (WALManager wal = wal(walDir)) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            WALStorageEngine storage = new WALStorageEngine(
                    wal, manager);
            storage.put(bytes("k1"), bytes("v1"));
            manager.rotate();
            storage.put(bytes("k2"), bytes("v2"));
        }
        // 崩溃后：新 manager + WAL 重放进 active，全部数据可读
        try (WALManager wal = wal(walDir)) {
            MemTableManager manager = new MemTableManager(
                    new MemoryManager(1 << 30), wal);
            wal.recover(manager.active());
            assertThat(manager.get(bytes("k1")))
                    .isEqualTo(bytes("v1"));
            assertThat(manager.get(bytes("k2")))
                    .isEqualTo(bytes("v2"));
        }
    }
}
