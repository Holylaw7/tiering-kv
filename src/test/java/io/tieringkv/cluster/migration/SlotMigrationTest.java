package io.tieringkv.cluster.migration;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 在线 Slot 迁移（ADR-0043）：复制/校验/切换/清理/断点续传。 */
class SlotMigrationTest {

    @TempDir
    Path dir;

    @Test
    void migrateCopiesAllEntriesToTarget() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 100; i++) {
                source.put(key(i), value(i));
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t1", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            runToCompletion(manager, task);
            assertThat(target.size()).isEqualTo(100);
            assertThat(target.get(key(42))).isEqualTo(value(42));
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void migrateFiltersBySlotRange() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            List<byte[]> inRange = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                byte[] k = key(i);
                source.put(k, value(i));
                if (HashSlotRouter.slot(k) <= 5000) {
                    inRange.add(k);
                }
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t2", 0, 5000, 1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            runToCompletion(manager, task);
            assertThat(target.size()).isEqualTo(inRange.size());
            for (byte[] k : inRange) {
                assertThat(target.get(k)).isNotNull();
            }
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void migratePreservesValues() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            source.put(bytes("a"), bytes("value-a"));
            source.put(bytes("b"), bytes("value-b"));
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t3", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            runToCompletion(manager, task);
            assertThat(target.get(bytes("a"))).isEqualTo(bytes("value-a"));
            assertThat(target.get(bytes("b"))).isEqualTo(bytes("value-b"));
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void verifyRejectsChecksumMismatch() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 20; i++) {
                source.put(key(i), value(i));
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t4", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            manager.runBatch(task, 20); // COPYING 完成 → VERIFYING
            target.delete(key(5)); // 篡改目标
            assertThat(manager.runBatch(task, 20)).isEqualTo(MigrationState.FAILED);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void resumeFromCheckpointCompletes() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 50; i++) {
                source.put(key(i), value(i));
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t5", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager first = new SlotMigrationManager(slotTable, dir);
            first.start(task);
            first.runBatch(task, 10);
            assertThat(task.state()).isEqualTo(MigrationState.COPYING);

            // 模拟进程重启：新 manager + 新 task 从 checkpoint 续传
            MigrationTask resumed = new MigrationTask("t5", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager second = new SlotMigrationManager(slotTable, dir);
            second.resume(resumed);
            assertThat(resumed.checkpoint().copiedEntries()).isGreaterThan(0);
            runToCompletion(second, resumed);
            assertThat(target.size()).isEqualTo(50);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void switchingUpdatesSlotTable() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 10; i++) {
                source.put(key(i), value(i));
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            int before = slotTable.shardFor(100);
            MigrationTask task = new MigrationTask("t6", 0, 5000, 1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            runToCompletion(manager, task);
            assertThat(slotTable.shardFor(100)).isEqualTo(1);
            assertThat(slotTable.shardFor(6000)).isEqualTo(before);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void sourceCleanedAfterDone() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 30; i++) {
                source.put(key(i), value(i));
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t7", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            runToCompletion(manager, task);
            assertThat(source.size()).isZero();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void stateMachineOrder() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            source.put(bytes("only"), bytes("one"));
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t8", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            List<MigrationState> states = new ArrayList<>();
            MigrationState state = manager.start(task);
            states.add(state);
            while (state != MigrationState.DONE && state != MigrationState.FAILED) {
                state = manager.runBatch(task, 100);
                states.add(state);
            }
            assertThat(states).containsExactly(
                    MigrationState.COPYING, MigrationState.VERIFYING,
                    MigrationState.SWITCHING, MigrationState.DONE);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void checkpointPersistedAndReloaded() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 30; i++) {
                source.put(key(i), value(i));
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t9", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            manager.runBatch(task, 7);
            assertThat(java.nio.file.Files.exists(dir.resolve("checkpoint-t9.bin"))).isTrue();
            MigrationTask reloaded = new MigrationTask("t9", 0,
                    HashSlotRouter.SLOT_COUNT - 1, 1, source, target);
            new SlotMigrationManager(slotTable, dir).resume(reloaded);
            assertThat(reloaded.checkpoint().copiedEntries()).isEqualTo(7);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void emptyRangeMigrationCompletes() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            // 全部键的 slot 都不在 [0,0]（用空源同样覆盖）
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t10", 0, 0, 1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            runToCompletion(manager, task);
            assertThat(task.state()).isEqualTo(MigrationState.DONE);
            assertThat(target.size()).isZero();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void failureKeepsSourceIntact() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 20; i++) {
                source.put(key(i), value(i));
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("t11", 0, HashSlotRouter.SLOT_COUNT - 1,
                    1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, dir);
            manager.start(task);
            manager.runBatch(task, 20);
            target.delete(key(3));
            assertThat(manager.runBatch(task, 20)).isEqualTo(MigrationState.FAILED);
            assertThat(source.size()).isEqualTo(20);
        } finally {
            source.close();
            target.close();
        }
    }

    private static void runToCompletion(SlotMigrationManager manager, MigrationTask task)
            throws Exception {
        MigrationState state = task.state();
        while (state != MigrationState.DONE && state != MigrationState.FAILED) {
            state = manager.runBatch(task, 1000);
        }
        assertThat(state).isEqualTo(MigrationState.DONE);
    }

    private static byte[] key(int i) {
        return ("migration:key:" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
