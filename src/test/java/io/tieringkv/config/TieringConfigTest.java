package io.tieringkv.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TieringConfigTest {

    @TempDir
    Path dir;

    @Test
    void loadsApplicationYaml() {
        TieringConfig config = TieringConfig.load(Path.of("config/application.yaml"));
        assertThat(config.server().port()).isEqualTo(6379);
        assertThat(config.wal().fsyncPolicy()).isEqualTo("EVERY_SEC");
        assertThat(config.cache().blockCacheCapacity()).isEqualTo(1024);
        assertThat(config.describe()).contains("server.port=6379");
    }

    @Test
    void missingFileFallsBackToDefaults() {
        TieringConfig config = TieringConfig.load(dir.resolve("missing.yaml"));
        assertThat(config.server().port()).isEqualTo(6379);
        assertThat(config.tiering().highWatermark()).isEqualTo(0.85);
    }
}
