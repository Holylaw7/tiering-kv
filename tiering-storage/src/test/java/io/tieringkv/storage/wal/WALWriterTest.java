package io.tieringkv.storage.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WALWriterTest {

    @TempDir
    Path dir;

    @Test
    void writesRecordsToFirstSegment() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            for (int i = 0; i < 5; i++) {
                wal.append(WALEntry.put(i, ("k" + i).getBytes(), "v".getBytes(), -1, i));
            }
        }
        Path segment = dir.resolve("000001.log");
        assertThat(Files.exists(segment)).isTrue();
        assertThat(Files.size(segment)).isGreaterThan(0);
    }

    @Test
    void alwaysPolicyForcesAfterEachAppend() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.ALWAYS);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "k".getBytes(), "v".getBytes(), -1, 1L));
        }
        assertThat(Files.size(dir.resolve("000001.log"))).isGreaterThan(0);
    }
}
