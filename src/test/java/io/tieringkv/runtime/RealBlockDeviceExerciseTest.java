package io.tieringkv.runtime;

import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.RecoveryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.storage.wal.WalWriteException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实块设备磁盘故障闭环演练（ADR-0342 / TD-049）：
 * 仅 Linux + TIERINGKV_CONTAINER_CHAOS=true 时运行（CI Runner），
 * 本地自动跳过。在 block-device-chaos.sh 的 loop 设备挂载点上执行
 * WAL 写入 → 恢复，覆盖 baseline / disk-full / readonly 三场景。
 */
@Tag("container")
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "TIERINGKV_CONTAINER_CHAOS",
        matches = "true")
class RealBlockDeviceExerciseTest {

    private static Path mount() {
        return Path.of(System.getenv().getOrDefault(
                "TIERINGKV_BLOCK_MOUNT",
                "/mnt/tiering-kv-block"));
    }

    @Test
    void baselineWriteAndRecoverOnBlockDevice() throws Exception {
        Path dir = exerciseDir("baseline");
        writeBatch(dir, 200);
        assertThat(recover(dir).size()).isEqualTo(200);
    }

    @Test
    void diskFullAppendFailsWithoutLoss() throws Exception {
        Path dir = exerciseDir("diskfull");
        writeBatch(dir, 100);

        Path[] fill = new Path[1];
        assertThatThrownBy(() -> fillUntilFull(mount(), fill))
                .isInstanceOf(IOException.class);
        // 前置校验：文件系统确实已满（避免填充满度不足造成误判）
        long usable = Files.getFileStore(mount()).getUsableSpace();
        // ext4 即使 -m 0 也会为非 root 调用者保留最后一小块（≤1 块，
        // 实测 4096B）；断言放宽到 1 块容差，语义仍为“磁盘已满”。
        assertThat(usable).as("fill 后可用空间应≤1 块，实际=%d", usable)
                .isLessThanOrEqualTo(4096);
        Path probe = mount().resolve("probe-" + System.nanoTime());
        assertThatThrownBy(() -> Files.write(probe,
                new byte[4096])).isInstanceOf(IOException.class);
        Files.deleteIfExists(probe);
        // 磁盘满：新 WAL 打开/追加必须失败（不允许静默丢失）
        assertThatThrownBy(() -> writeBatch(
                exerciseDir("diskfull-failed"), 5))
                .isInstanceOfAny(IOException.class,
                        WalWriteException.class);
        Files.deleteIfExists(fill[0]);

        assertThat(recover(dir).size()).isEqualTo(100);
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "TIERINGKV_BLOCK_READONLY", matches = "true")
    void readonlyAppendFailsWithoutLoss() throws Exception {
        Path dir = mount().resolve("exercise-baseline");
        assertThat(Files.isDirectory(dir))
                .as("baseline 演练必须先于 readonly 运行")
                .isTrue();
        // 只读挂载下：既有 WAL 可恢复
        assertThat(recover(dir).size()).isEqualTo(200);
        // 新写入必须失败
        assertThatThrownBy(() -> writeBatch(
                mount().resolve("exercise-readonly-new"), 5))
                .isInstanceOfAny(IOException.class,
                        WalWriteException.class);
    }

    private static Path exerciseDir(String name) throws IOException {
        Path dir = mount().resolve("exercise-" + name);
        if (Files.exists(dir)) {
            deleteRecursively(dir);
        }
        Files.createDirectories(dir);
        return dir;
    }

    private static void writeBatch(Path dir, int count)
            throws Exception {
        WALConfig config = new WALConfig(dir, 1 << 20,
                WALConfig.FsyncPolicy.ALWAYS);
        MemTable memTable = MemTable.create();
        try (WALManager wal = new WALManager(config)) {
            WALStorageEngine storage =
                    new WALStorageEngine(wal, memTable);
            for (int i = 0; i < count; i++) {
                storage.put(bytes("k" + i), bytes("v" + i));
            }
        }
    }

    private static MemTable recover(Path dir) throws Exception {
        WALConfig config = new WALConfig(dir, 1 << 20,
                WALConfig.FsyncPolicy.ALWAYS);
        MemTable memTable = MemTable.create();
        new RecoveryManager(config).recover(memTable);
        return memTable;
    }

    /**
     * 持续写入小块直到 ENOSPC；文件路径经 holder 回传。
     * 块大小 1KB：ENOSPC 时剩余空间必然 <1KB，后续任何新块分配失败。
     */
    private static void fillUntilFull(Path mountDir, Path[] holder)
            throws IOException {
        Path file = Files.createTempFile(mountDir, "fill-", ".bin");
        holder[0] = file;
        byte[] block = new byte[1024];
        try (OutputStream out = Files.newOutputStream(file,
                StandardOpenOption.APPEND)) {
            while (true) {
                out.write(block);
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 演练目录清理尽力而为
                }
            });
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
