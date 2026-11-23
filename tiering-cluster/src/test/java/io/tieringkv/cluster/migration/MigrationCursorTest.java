package io.tieringkv.cluster.migration;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 游标迁移（ADR-0045）：单次迭代/暂停/恢复/崩溃续传/CRC 游标文件。 */
class MigrationCursorTest {

    @TempDir
    Path dir;

    @Test
    void cursorAdvancesOnCopy() throws Exception {
        MemTable source = source(20);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("c1", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            manager.runBatch(task, 5);
            assertThat(task.cursor().checkpointOffset()).isEqualTo(5);
            assertThat(task.cursor().lastKey()).isNotEmpty();
            assertThat(task.checkpoint().copiedEntries()).isEqualTo(5);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void lastVersionTracked() throws Exception {
        MemTable source = source(3);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("c2", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            runToDone(manager, task);
            assertThat(task.cursor().lastVersion()).isGreaterThanOrEqualTo(0);
            assertThat(task.cursor().checkpointOffset()).isEqualTo(3);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void pauseKeepsStateAndPersistsCursor() throws Exception {
        MemTable source = source(30);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("c3", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            manager.runBatch(task, 10);
            assertThat(manager.pause(task)).isEqualTo(MigrationState.PAUSED);
            assertThat(task.state()).isEqualTo(MigrationState.PAUSED);
            assertThat(Files.exists(dir.resolve("slot-0.cursor"))).isTrue();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void resumeContinuesCopy() throws Exception {
        MemTable source = source(30);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("c4", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            manager.runBatch(task, 10);
            manager.pause(task);
            assertThat(manager.resume(task)).isEqualTo(MigrationState.COPYING);
            runToDone(manager, task);
            assertThat(target.size()).isEqualTo(30);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void pausedTaskDoesNotResumeOnRunBatch() throws Exception {
        MemTable source = source(10);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("c5", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            manager.runBatch(task, 5);
            manager.pause(task);
            assertThat(manager.runBatch(task, 100)).isEqualTo(MigrationState.PAUSED);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void recoverFromCursorFileResumes() throws Exception {
        MemTable source = source(50);
        MemTable target = MemTable.create();
        try {
            MigrationTask first = task("c6", source, target);
            SlotMigrationManager manager = manager();
            manager.start(first);
            manager.runBatch(first, 15);
            manager.pause(first);

            // 崩溃恢复语义：同一 target 继续接收剩余数据
            MigrationTask recovered = task("c6", source, target);
            SlotMigrationManager second = manager();
            second.recover(recovered);
            assertThat(recovered.state()).isEqualTo(MigrationState.COPYING);
            assertThat(recovered.cursor().checkpointOffset()).isEqualTo(15);
            runToDone(second, recovered);
            assertThat(target.size()).isEqualTo(50);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void corruptCursorFileRecoverFallsBackToStart() throws Exception {
        MemTable source = source(10);
        MemTable target = MemTable.create();
        try {
            MigrationTask first = task("c7", source, target);
            SlotMigrationManager manager = manager();
            manager.start(first);
            manager.runBatch(first, 5);
            byte[] bytes = Files.readAllBytes(dir.resolve("slot-0.cursor"));
            bytes[bytes.length - 1] ^= 0x01;
            Files.write(dir.resolve("slot-0.cursor"), bytes);

            MemTable target2 = MemTable.create();
            try {
                MigrationTask recovered = task("c7", source, target2);
                manager().recover(recovered);
                assertThat(recovered.cursor().checkpointOffset()).isZero();
                assertThat(recovered.state()).isEqualTo(MigrationState.COPYING);
            } finally {
                target2.close();
            }
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void singleIteratorReusedAcrossBatches() throws Exception {
        // 游标模型：连续批次不重建快照（通过 checkpointOffset 单调推进验证单次扫描）
        MemTable source = source(100);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("c8", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            long previous = -1;
            for (int i = 0; i < 5; i++) {
                manager.runBatch(task, 20);
                assertThat(task.cursor().checkpointOffset()).isGreaterThan(previous);
                previous = task.cursor().checkpointOffset();
            }
            assertThat(previous).isEqualTo(100);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void resumeDoesNotCopyDuplicates() throws Exception {
        MemTable source = source(40);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("c9", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            manager.runBatch(task, 12);
            long copiedBefore = task.checkpoint().copiedEntries();
            manager.pause(task);
            manager.resume(task);
            manager.runBatch(task, 100);
            assertThat(task.checkpoint().copiedEntries()).isEqualTo(40);
            assertThat(task.checkpoint().copiedEntries()).isGreaterThan(copiedBefore);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void multipleTasksHaveIsolatedCursors() throws Exception {
        MemTable sourceA = source(50);
        MemTable sourceB = source(50);
        MemTable targetA = MemTable.create();
        MemTable targetB = MemTable.create();
        try {
            MigrationTask a = task("a", sourceA, targetA);
            MigrationTask b = task("b", sourceB, targetB);
            SlotMigrationManager manager = manager();
            manager.start(a);
            manager.start(b);
            manager.runBatch(a, 10);
            manager.runBatch(b, 20);
            assertThat(a.cursor().checkpointOffset()).isEqualTo(10);
            assertThat(b.cursor().checkpointOffset()).isEqualTo(20);
            runToDone(manager, a);
            runToDone(manager, b);
        } finally {
            sourceA.close();
            sourceB.close();
            targetA.close();
            targetB.close();
        }
    }

    @Test
    void emptySourceCompletesImmediately() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("e", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            runToDone(manager, task);
            assertThat(task.state()).isEqualTo(MigrationState.DONE);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void verifyStillRejectsTamperedTarget() throws Exception {
        MemTable source = source(20);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = task("v", source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            manager.runBatch(task, 100);
            target.delete(key(3));
            assertThat(manager.runBatch(task, 100)).isEqualTo(MigrationState.FAILED);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void doneStateRecoverReturnsDone() throws Exception {
        MemTable source = source(5);
        MemTable target = MemTable.create();
        try {
            MigrationTask first = task("d1", source, target);
            SlotMigrationManager manager = manager();
            manager.start(first);
            runToDone(manager, first);
            MigrationTask recovered = task("d1", source, MemTable.create());
            manager().recover(recovered);
            assertThat(recovered.state()).isEqualTo(MigrationState.DONE);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void crashRecoveryMidCopyCompletes() throws Exception {
        MemTable source = source(80);
        MemTable target = MemTable.create();
        try {
            // 模拟进程崩溃：复制到一半直接丢弃 manager（不 pause）
            MigrationTask first = task("crash", source, target);
            SlotMigrationManager manager = manager();
            manager.start(first);
            manager.runBatch(first, 25);
            assertThat(first.state()).isEqualTo(MigrationState.COPYING);

            // 新进程：recover 游标 → 续传完成
            MigrationTask recovered = task("crash", source, target);
            SlotMigrationManager second = manager();
            second.recover(recovered);
            runToDone(second, recovered);
            assertThat(target.size()).isEqualTo(80);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void cursorFileNamedBySlotStart() throws Exception {
        MemTable source = source(3);
        MemTable target = MemTable.create();
        try {
            MigrationTask task = new MigrationTask("n", 100, 200, 1, source, target);
            SlotMigrationManager manager = manager();
            manager.start(task);
            runToDone(manager, task);
            assertThat(Files.exists(dir.resolve("slot-100.cursor"))).isTrue();
        } finally {
            source.close();
            target.close();
        }
    }

    private SlotMigrationManager manager() {
        SlotTable slotTable = new SlotTable();
        slotTable.assignShards(2);
        return new SlotMigrationManager(slotTable, dir);
    }

    private static MemTable source(int count) {
        MemTable source = MemTable.create();
        for (int i = 0; i < count; i++) {
            source.put(key(i), ("v" + i).getBytes(StandardCharsets.UTF_8));
        }
        return source;
    }

    private static MigrationTask task(String id, MemTable source, MemTable target) {
        return new MigrationTask(id, 0, HashSlotRouter.SLOT_COUNT - 1, 1, source, target);
    }

    private static void runToDone(SlotMigrationManager manager, MigrationTask task)
            throws Exception {
        MigrationState state = task.state();
        while (state != MigrationState.DONE && state != MigrationState.FAILED) {
            state = manager.runBatch(task, 1000);
        }
        assertThat(state).isEqualTo(MigrationState.DONE);
    }

    private static byte[] key(int i) {
        return ("migration:cursor:" + i).getBytes(StandardCharsets.UTF_8);
    }
}
