package io.tieringkv.storage.tiering;

import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationRecoveryTest {

    @TempDir
    Path dir;

    private static MigrationTask task(String key, long version, MigrationTask.Status status) {
        KeyValueEntry entry = new KeyValueEntry(
                key.getBytes(StandardCharsets.UTF_8), new byte[]{1}, 0, 0, -1, version, false, 66);
        return new MigrationTask(entry, "memory", "cold", 0, status);
    }

    @Test
    void recoversOnlyUnfinishedTasks() throws Exception {
        try (MigrationLog log = new MigrationLog(dir)) {
            log.append(task("k1", 1, MigrationTask.Status.PENDING));
            log.append(task("k1", 1, MigrationTask.Status.RUNNING));
            log.append(task("k1", 1, MigrationTask.Status.SUCCESS));
            log.append(task("k2", 2, MigrationTask.Status.PENDING));
            log.append(task("k3", 3, MigrationTask.Status.RETRY));
            log.append(task("k4", 4, MigrationTask.Status.FAILED));
        }
        long sizeBefore;
        try (MigrationLog log = new MigrationLog(dir)) {
            List<MigrationTask> unfinished = log.recover();
            assertThat(unfinished).hasSize(2);
            assertThat(new String(unfinished.get(0).key(), StandardCharsets.UTF_8)).isEqualTo("k2");
            assertThat(new String(unfinished.get(1).key(), StandardCharsets.UTF_8)).isEqualTo("k3");
            sizeBefore = Files.size(dir.resolve("migration.log"));
            log.compact(unfinished);
        }
        try (MigrationLog log = new MigrationLog(dir)) {
            assertThat(log.recover()).hasSize(2);
            assertThat(Files.size(dir.resolve("migration.log"))).isLessThan(sizeBefore);
        }
    }

    @Test
    void corruptTailStopsScan() throws Exception {
        try (MigrationLog log = new MigrationLog(dir)) {
            log.append(task("k1", 1, MigrationTask.Status.PENDING));
            log.append(task("k2", 2, MigrationTask.Status.PENDING));
        }
        Files.write(dir.resolve("migration.log"), new byte[]{1, 2, 3},
                StandardOpenOption.APPEND);
        try (MigrationLog log = new MigrationLog(dir)) {
            List<MigrationTask> unfinished = log.recover();
            assertThat(unfinished).hasSize(2); // 损坏尾部被忽略
        }
    }
}
