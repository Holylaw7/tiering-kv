package io.tieringkv.storage.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WALReaderTest {

    @TempDir
    Path dir;

    @Test
    void readsRecordsInOrderUntilEof() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        List<WALEntry> entries = List.of(
                WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L),
                WALEntry.delete(2L, "b".getBytes(), 2L),
                WALEntry.put(3L, "c".getBytes(), "3".getBytes(), 1000, 3L));
        try (WALManager wal = new WALManager(config)) {
            for (WALEntry entry : entries) {
                wal.append(entry);
            }
        }

        try (WALReader reader = new WALReader(dir.resolve("000001.log"))) {
            for (WALEntry expected : entries) {
                assertThat(reader.next()).isEqualTo(expected);
            }
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void truncatedTailReturnsNull() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L));
        }
        Files.write(dir.resolve("000001.log"), new byte[]{1, 2, 3},
                StandardOpenOption.APPEND);
        try (WALReader reader = new WALReader(dir.resolve("000001.log"))) {
            assertThat(reader.next()).isNotNull();
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void corruptedRecordThrows() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        try (WALManager wal = new WALManager(config)) {
            wal.append(WALEntry.put(1L, "a".getBytes(), "1".getBytes(), -1, 1L));
            wal.append(WALEntry.put(2L, "b".getBytes(), "2".getBytes(), -1, 2L));
        }
        Path path = dir.resolve("000001.log");
        byte[] bytes = Files.readAllBytes(path);
        int firstRecordLength = WALRecord.HEADER_SIZE + 1 + 1 + WALRecord.CHECKSUM_SIZE;
        bytes[firstRecordLength + 10] ^= 0x01; // 损坏第二条记录 payload
        Files.write(path, bytes);

        try (WALReader reader = new WALReader(path)) {
            assertThat(reader.next()).isNotNull();
            assertThatThrownBy(reader::next).isInstanceOf(WalCorruptionException.class);
        }
    }
}
