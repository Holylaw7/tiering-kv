package io.tieringkv.storage.cold;

import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SSTableReaderTest {

    @TempDir
    Path dir;

    @Test
    void getHitsAndMisses() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(5000);
        SSTableMeta meta = write(entries, 1);
        try (SSTableReader reader = SSTableReader.open(meta, dir)) {
            assertThat(reader.get("k01234".getBytes(StandardCharsets.UTF_8)).value())
                    .isEqualTo("v1234".getBytes(StandardCharsets.UTF_8));
            assertThat(reader.get("absent".getBytes(StandardCharsets.UTF_8))).isNull();
        }
    }

    @Test
    void getFindsValuesAcrossBlocks() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(100_000);
        SSTableMeta meta = write(entries, 2);
        try (SSTableReader reader = SSTableReader.open(meta, dir)) {
            assertThat(reader.get("k00000".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(reader.get("k50000".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(reader.get("k99999".getBytes(StandardCharsets.UTF_8))).isNotNull();
        }
    }

    @Test
    void corruptedBlockIsDetected() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(5000);
        SSTableMeta meta = write(entries, 3);
        byte[] bytes = Files.readAllBytes(meta.path(dir));
        bytes[100] ^= 0x01; // 损坏第一个数据块
        Files.write(meta.path(dir), bytes);

        try (SSTableReader reader = SSTableReader.open(meta, dir)) {
            assertThatThrownBy(() -> reader.get("k00001".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ColdCorruptionException.class);
        }
    }

    @Test
    void iteratorReadsAllEntriesInOrder() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(5000);
        SSTableMeta meta = write(entries, 4);
        try (SSTableReader reader = SSTableReader.open(meta, dir);
             StorageIterator iterator = reader.iterator()) {
            int count = 0;
            byte[] previous = null;
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                if (previous != null) {
                    assertThat(Keys.compare(previous, entry.key())).isLessThan(0);
                }
                previous = entry.key();
                count++;
            }
            assertThat(count).isEqualTo(5000);
        }
    }

    private SSTableMeta write(List<KeyValueEntry> entries, long id) throws Exception {
        try (SSTableWriter writer = new SSTableWriter(dir, id, entries.size(), 10, 4096)) {
            for (KeyValueEntry entry : entries) {
                writer.writeEntry(entry);
            }
            return writer.finish();
        }
    }
}
