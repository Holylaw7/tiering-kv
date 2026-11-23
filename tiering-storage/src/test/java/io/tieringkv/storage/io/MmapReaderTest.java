package io.tieringkv.storage.io;

import io.tieringkv.storage.cold.ColdCorruptionException;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.cold.SSTableWriterTest;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MmapReaderTest {

    @TempDir
    Path dir;

    @Test
    void mmapReadsValuesCorrectly() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(5000);
        SSTableMeta meta;
        try (SSTableWriter writer = new SSTableWriter(dir, 1, entries.size(), 10, 4096)) {
            for (KeyValueEntry entry : entries) {
                writer.writeEntry(entry);
            }
            meta = writer.finish();
        }
        try (MmapSSTableReader reader = MmapSSTableReader.open(meta, dir)) {
            assertThat(reader.get("k00000".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(reader.get("k02500".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(reader.get("k04999".getBytes(StandardCharsets.UTF_8))).isNotNull();
            assertThat(reader.get("absent".getBytes(StandardCharsets.UTF_8))).isNull();
        }
    }

    @Test
    void corruptedBlockIsDetected() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(5000);
        SSTableMeta meta;
        try (SSTableWriter writer = new SSTableWriter(dir, 2, entries.size(), 10, 4096)) {
            for (KeyValueEntry entry : entries) {
                writer.writeEntry(entry);
            }
            meta = writer.finish();
        }
        byte[] bytes = Files.readAllBytes(meta.path(dir));
        bytes[100] ^= 0x01;
        Files.write(meta.path(dir), bytes);
        try (MmapSSTableReader reader = MmapSSTableReader.open(meta, dir)) {
            assertThatThrownBy(() -> reader.get("k00001".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ColdCorruptionException.class);
        }
    }

    @Test
    void truncatedFooterIsRejected() throws Exception {
        List<KeyValueEntry> entries = SSTableWriterTest.entries(100);
        SSTableMeta meta;
        try (SSTableWriter writer = new SSTableWriter(dir, 3, entries.size(), 10, 4096)) {
            for (KeyValueEntry entry : entries) {
                writer.writeEntry(entry);
            }
            meta = writer.finish();
        }
        byte[] all = Files.readAllBytes(meta.path(dir));
        Files.write(meta.path(dir), Arrays.copyOf(all, all.length - 10));
        assertThatThrownBy(() -> MmapSSTableReader.open(meta, dir))
                .isInstanceOf(ColdCorruptionException.class);
    }
}
