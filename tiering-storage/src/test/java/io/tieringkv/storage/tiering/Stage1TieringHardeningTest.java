package io.tieringkv.storage.tiering;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.CheckpointManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALEntry;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class Stage1TieringHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void expiredHotEntryDoesNotFallBackToColdStorage() throws Exception {
        AtomicLong clock = new AtomicLong(10_000L);
        MemoryManager memory = new MemoryManager(1024 * 1024);
        MemTable memTable = MemTable.createForTest(clock::get, memory);

        try (ColdStorageEngine cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(tempDir.resolve("cold")))) {
            cold.writeTable(List.of(KeyValueEntry.live(
                    bytes("k"), bytes("cold-v"), 1_000L, StorageEngine.NO_TTL, 1L)));

            TieringReadStorageEngine tiered = new TieringReadStorageEngine(memTable, cold);
            assertThat(tiered.get(bytes("k"))).isEqualTo(bytes("cold-v"));

            // Overwrite k in hot tier with short TTL, then advance clock past expiry
            memTable.put(bytes("k"), bytes("hot-v"), 500L);
            assertThat(tiered.get(bytes("k"))).isEqualTo(bytes("hot-v"));

            clock.addAndGet(1_000L);
            assertThat(tiered.get(bytes("k"))).isNull();
            assertThat(tiered.exists(bytes("k"))).isFalse();
            assertThat(tiered.ttlMillis(bytes("k"))).isNegative();
            assertThat(tiered.persist(bytes("k"))).isFalse();
        }
    }

    @Test
    void clearWipesHotWalAndColdData() throws Exception {
        Path walDir = tempDir.resolve("wal");
        Path coldDir = tempDir.resolve("cold");
        MemoryManager memory = new MemoryManager(1024 * 1024);
        MemTable memTable = MemTable.create(memory);

        try (WALManager wal = new WALManager(new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO));
             ColdStorageEngine cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(coldDir))) {
            WALStorageEngine walEngine = new WALStorageEngine(wal, memTable);
            TieringReadStorageEngine tiered = new TieringReadStorageEngine(walEngine, cold);

            tiered.put(bytes("hot-k"), bytes("hot-v"));
            cold.writeTable(List.of(KeyValueEntry.live(
                    bytes("cold-k"), bytes("cold-v"), System.currentTimeMillis(), StorageEngine.NO_TTL, 1L)));

            tiered.clear();

            assertThat(tiered.get(bytes("hot-k"))).isNull();
            assertThat(tiered.get(bytes("cold-k"))).isNull();
            assertThat(cold.tablesSnapshot()).isEmpty();
            wal.flushAndForce();
        }

        // Recover WAL into a clean MemTable and verify cleared hot key does not resurrect
        MemTable recovered = MemTable.create();
        try (WALManager wal = new WALManager(new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO))) {
            wal.recover(recovered);
        }
        assertThat(recovered.get(bytes("hot-k"))).isNull();
    }

    @Test
    void checkpointEmptyValueAndWalRecoveryExpiredPutTombstone() throws Exception {
        Path ckptDir = Files.createDirectories(tempDir.resolve("ckpt"));
        KeyValueEntry emptyValEntry = KeyValueEntry.live(
                bytes("empty-k"), new byte[0], 5_000L, StorageEngine.NO_TTL, 1L);
        CheckpointManager.write(ckptDir, new WALManager.WALPosition(1L, 0L), List.of(emptyValEntry));
        CheckpointManager.Checkpoint loaded = CheckpointManager.read(ckptDir);
        MemTable restored = MemTable.create();
        CheckpointManager.restore(restored, loaded);

        byte[] readBack = restored.get(bytes("empty-k"));
        assertThat(readBack).isNotNull().isEmpty();

        Path walDir = tempDir.resolve("wal-expired");
        Path coldDir = tempDir.resolve("cold-expired");
        try (WALManager wal = new WALManager(new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO))) {
            long pastTs = System.currentTimeMillis() - 10_000L;
            wal.append(WALEntry.put(pastTs, bytes("ttl-k"), bytes("expired-v"), 100L, 2L));
            wal.flushAndForce();
        }

        MemTable hotAfterRecover = MemTable.create();
        try (WALManager wal = new WALManager(new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO));
             ColdStorageEngine cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(coldDir))) {
            cold.writeTable(List.of(KeyValueEntry.live(
                    bytes("ttl-k"), bytes("stale-cold"), 1_000L, StorageEngine.NO_TTL, 1L)));
            wal.recover(hotAfterRecover);

            TieringReadStorageEngine tiered = new TieringReadStorageEngine(hotAfterRecover, cold);
            assertThat(hotAfterRecover.hasTombstone(bytes("ttl-k"))).isTrue();
            assertThat(tiered.get(bytes("ttl-k"))).isNull();
        }
    }

    @Test
    void controllerRefreshesGaugesAfterMigrationAndFlush() throws Exception {
        Path walDir = tempDir.resolve("wal-ctrl");
        Path coldDir = tempDir.resolve("cold-ctrl");
        Path migDir = tempDir.resolve("mig-ctrl");

        MemoryManager memory = new MemoryManager(1024 * 1024);
        MemTable memTable = MemTable.create(memory);

        ColdStorageEngine.Config coldConfig = new ColdStorageEngine.Config(coldDir, 4096, 10.0, 256L, 10);
        try (WALManager wal = new WALManager(new WALConfig(walDir, 1 << 20, WALConfig.FsyncPolicy.NO));
             ColdStorageEngine cold = new ColdStorageEngine(coldConfig);
             TieringController controller = new TieringController(
                     TieringController.Config.defaults(migDir), memory, memTable, wal, cold)) {
            WALStorageEngine walEngine = new WALStorageEngine(wal, memTable);
            walEngine.put(bytes("mig-k"), bytes("mig-v"));

            KeyValueEntry entry = memTable.getEntry(bytes("mig-k"));
            assertThat(entry).isNotNull();
            boolean accepted = controller.migrationScheduler().submit(MigrationTask.pending(entry, "HOT", "COLD"));
            assertThat(accepted).isTrue();

            long deadline = System.currentTimeMillis() + 5_000L;
            while ((controller.metrics().snapshot().migrationSuccess() < 1
                    || controller.metrics().snapshot().coldPendingEntries() < 1)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(controller.metrics().snapshot().migrationSuccess()).isGreaterThanOrEqualTo(1);
            assertThat(controller.metrics().snapshot().coldPendingEntries()).isGreaterThanOrEqualTo(1);

            walEngine.put(bytes("flush-k"), "x".repeat(300).getBytes(StandardCharsets.UTF_8));
            controller.flushScheduler().scheduleFlush();
            long flushDeadline = System.currentTimeMillis() + 5_000L;
            StorageMetrics.Snapshot snapshot = controller.metrics().snapshot();
            while ((snapshot.coldSstableCount() < 1
                    || snapshot.flushCount() < 1
                    || snapshot.flushInProgress() != 0
                    || snapshot.memoryEntryCount() != 0)
                    && System.currentTimeMillis() < flushDeadline) {
                Thread.sleep(20);
                snapshot = controller.metrics().snapshot();
            }
            assertThat(snapshot.coldSstableCount()).isGreaterThanOrEqualTo(1);
            assertThat(snapshot.coldPendingEntries()).isEqualTo(1L);
            assertThat(snapshot.memoryEntryCount()).isEqualTo(memTable.size()).isZero();

            // Second migration exceeding pendingThresholdBytes flushes pending into an SSTable and resets pending to 0
            walEngine.put(bytes("mig-k2"), "y".repeat(300).getBytes(StandardCharsets.UTF_8));
            KeyValueEntry entry2 = memTable.getEntry(bytes("mig-k2"));
            assertThat(entry2).isNotNull();
            assertThat(controller.migrationScheduler().submit(MigrationTask.pending(entry2, "HOT", "COLD"))).isTrue();
            long mig2Deadline = System.currentTimeMillis() + 5_000L;
            StorageMetrics.Snapshot afterMig2 = controller.metrics().snapshot();
            while ((afterMig2.migrationSuccess() < 2
                    || afterMig2.coldSstableCount() < 2
                    || afterMig2.coldPendingEntries() != 0)
                    && System.currentTimeMillis() < mig2Deadline) {
                Thread.sleep(20);
                afterMig2 = controller.metrics().snapshot();
            }
            assertThat(afterMig2.coldSstableCount()).isGreaterThanOrEqualTo(2);
            assertThat(afterMig2.coldPendingEntries()).isZero();
            assertThat(afterMig2.memoryEntryCount()).isEqualTo(memTable.size()).isZero();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
