package io.tieringkv.storage.wal;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentRotationTest {

    @TempDir
    Path dir;

    @Test
    void rollsSegmentsAndRecoversAcrossAll() throws Exception {
        WALConfig config = new WALConfig(dir, 256, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            for (int i = 0; i < 50; i++) {
                wal.append(WALEntry.put(i,
                        String.format("key-%03d", i).getBytes(StandardCharsets.UTF_8),
                        new byte[32], -1, i));
            }
        }

        List<Long> segments = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.log")) {
            for (Path path : stream) {
                segments.add(Long.parseLong(path.getFileName().toString().replace(".log", "")));
            }
        }
        assertThat(segments.size()).isGreaterThan(1);

        MemTable memTable = MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
        RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(memTable);
        assertThat(stats.recordsApplied()).isEqualTo(50);
        assertThat(memTable.size()).isEqualTo(50);
        assertThat(memTable.get("key-049".getBytes(StandardCharsets.UTF_8))).isNotNull();
    }
}
