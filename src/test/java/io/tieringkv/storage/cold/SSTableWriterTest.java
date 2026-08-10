package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SSTableWriterTest {

    @TempDir
    Path dir;

    @Test
    void writesFileWithMetadata() throws Exception {
        List<KeyValueEntry> entries = entries(1000);
        SSTableMeta meta;
        try (SSTableWriter writer = new SSTableWriter(dir, 1, entries.size(), 10, 4096)) {
            for (KeyValueEntry entry : entries) {
                writer.writeEntry(entry);
            }
            meta = writer.finish();
        }
        assertThat(meta.entryCount()).isEqualTo(1000);
        assertThat(meta.firstKey()).isEqualTo("k00000".getBytes(StandardCharsets.UTF_8));
        assertThat(meta.lastKey()).isEqualTo("k00999".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(meta.path(dir))).isTrue();
        assertThat(Files.size(meta.path(dir))).isEqualTo(meta.fileSize());
    }

    @Test
    void unfinishedWriterDeletesFile() throws Exception {
        SSTableWriter writer = new SSTableWriter(dir, 7, 10, 10, 4096);
        writer.writeEntry(KeyValueEntry.live(
                "k".getBytes(StandardCharsets.UTF_8), "v".getBytes(), 0, -1, 1));
        writer.close();
        assertThat(Files.exists(dir.resolve("00000007.sst"))).isFalse();
    }

    @Test
    void tombstoneEntriesRoundTrip() throws Exception {
        KeyValueEntry tombstone = KeyValueEntry.tombstone(
                "gone".getBytes(StandardCharsets.UTF_8), 0, 5);
        try (SSTableWriter writer = new SSTableWriter(dir, 2, 1, 10, 4096)) {
            writer.writeEntry(tombstone);
            writer.finish();
        }
        try (SSTableReader reader = SSTableReader.open(
                new SSTableMeta(2, "00000002.sst", 1, Files.size(dir.resolve("00000002.sst")),
                        tombstone.key(), tombstone.key()), dir)) {
            KeyValueEntry read = reader.get(tombstone.key());
            assertThat(read).isNotNull();
            assertThat(read.deleted()).isTrue();
            assertThat(read.value()).isNull();
        }
    }

    static List<KeyValueEntry> entries(int count) {
        List<KeyValueEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] key = String.format("k%05d", i).getBytes(StandardCharsets.UTF_8);
            entries.add(KeyValueEntry.live(key, ("v" + i).getBytes(), 0, -1, i));
        }
        return entries;
    }
}
