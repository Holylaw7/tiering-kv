package io.tieringkv.storage.io;

import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.cold.SSTableWriterTest;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileChannelReaderTest {

    @TempDir
    Path dir;

    @Test
    void baselineMatchesMmapResults() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(5000);
        SSTableMeta meta;
        try (SSTableWriter writer = new SSTableWriter(dir, 1, entries.size(), 10, 4096)) {
            for (KeyValueEntry entry : entries) {
                writer.writeEntry(entry);
            }
            meta = writer.finish();
        }
        try (FileChannelSSTableReader baseline = FileChannelSSTableReader.open(meta, dir);
             MmapSSTableReader mmap = MmapSSTableReader.open(meta, dir)) {
            for (int i : new int[]{0, 1234, 2500, 4999}) {
                byte[] key = String.format("k%05d", i).getBytes(StandardCharsets.UTF_8);
                assertThat(mmap.get(key).value()).isEqualTo(baseline.get(key).value());
            }
            assertThat(mmap.get("absent".getBytes(StandardCharsets.UTF_8))).isNull();
            assertThat(baseline.get("absent".getBytes(StandardCharsets.UTF_8))).isNull();
        }
    }
}
