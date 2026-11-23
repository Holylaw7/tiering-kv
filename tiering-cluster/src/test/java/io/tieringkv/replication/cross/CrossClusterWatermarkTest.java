package io.tieringkv.replication.cross;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 复制目标端水位（ADR-0321 M3 收尾）：记录 / 落盘 / 加载 / 幂等。 */
class CrossClusterWatermarkTest {

    @TempDir
    Path dir;

    @Test
    void recordAndShouldSkip() {
        CrossClusterWatermark watermark = new CrossClusterWatermark(
                dir.resolve("wm.bin"));
        assertThat(watermark.shouldSkip("r1", 5)).isFalse();
        watermark.record("r1", 5);
        assertThat(watermark.shouldSkip("r1", 5)).isTrue();
        assertThat(watermark.shouldSkip("r1", 4)).isTrue();
        assertThat(watermark.shouldSkip("r1", 6)).isFalse();
        assertThat(watermark.shouldSkip("r2", 5)).isFalse();
    }

    @Test
    void checkpointAndLoadRestoresWatermarks() throws Exception {
        Path file = dir.resolve("wm.bin");
        CrossClusterWatermark watermark =
                new CrossClusterWatermark(file);
        watermark.record("r1", 42);
        watermark.record("r2", 7);
        watermark.checkpoint();

        CrossClusterWatermark loaded =
                CrossClusterWatermark.load(file);
        assertThat(loaded.watermark("r1")).isEqualTo(42);
        assertThat(loaded.watermark("r2")).isEqualTo(7);
        assertThat(loaded.shouldSkip("r1", 42)).isTrue();
    }

    @Test
    void loadMissingFileReturnsEmpty() throws Exception {
        CrossClusterWatermark loaded = CrossClusterWatermark.load(
                dir.resolve("absent.bin"));
        assertThat(loaded.size()).isZero();
    }

    @Test
    void closeFlushesCheckpoint() throws Exception {
        Path file = dir.resolve("wm.bin");
        try (CrossClusterWatermark watermark =
                     new CrossClusterWatermark(file)) {
            watermark.record("r1", 9);
        }
        assertThat(Files.exists(file)).isTrue();
        assertThat(CrossClusterWatermark.load(file)
                .watermark("r1")).isEqualTo(9);
    }

    @Test
    void corruptedFileRejected() throws Exception {
        Path file = dir.resolve("wm.bin");
        CrossClusterWatermark watermark =
                new CrossClusterWatermark(file);
        watermark.record("r1", 1);
        watermark.checkpoint();
        byte[] bytes = Files.readAllBytes(file);
        bytes[4] ^= 0x7F;
        Files.write(file, bytes);
        assertThatThrownBy(() ->
                CrossClusterWatermark.load(file))
                .isInstanceOf(Exception.class);
    }
}
