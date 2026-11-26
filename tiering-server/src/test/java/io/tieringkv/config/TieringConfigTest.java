package io.tieringkv.config;

import io.tieringkv.storage.VectorIndexSyncStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TieringConfigTest {

    @TempDir
    Path dir;

    @Test
    void loadsApplicationYaml() {
        TieringConfig config = TieringConfig.load(Path.of("config/application.yaml"));
        assertThat(config.server().port()).isEqualTo(6379);
        assertThat(config.server().maxClients()).isEqualTo(10_000);
        assertThat(config.server().timeoutSeconds()).isZero();
        assertThat(config.server().tls().enabled()).isFalse();
        assertThat(config.wal().fsyncPolicy()).isEqualTo("EVERY_SEC");
        assertThat(config.cache().blockCacheCapacity()).isEqualTo(1024);
        assertThat(config.cache().evictionPolicy()).isEqualTo("sampled-lfu");
        assertThat(config.cache().evictionSamples()).isEqualTo(16);
        assertThat(config.describe()).contains(
                "server.port=6379", "server.max-clients=10000", "cache.eviction-policy=sampled-lfu");
    }

    @Test
    void validatesCustomEvictionPolicyAndRejectsUnknown() throws Exception {
        Path custom = dir.resolve("eviction.yaml");
        Files.writeString(custom, """
                cache:
                  eviction-policy: segment-lfu
                  eviction-samples: 32
                """);
        TieringConfig cfg = TieringConfig.load(custom);
        cfg.validate();
        assertThat(cfg.cache().evictionPolicy()).isEqualTo("segment-lfu");
        assertThat(cfg.cache().evictionSamples()).isEqualTo(32);

        Path invalid = dir.resolve("invalid-eviction.yaml");
        Files.writeString(invalid, """
                cache:
                  eviction-policy: unknown-policy
                """);
        TieringConfig bad = TieringConfig.load(invalid);
        assertThatThrownBy(bad::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eviction-policy");
    }

    @Test
    void loadsServerSecurityAndLimitsWithoutExposingPassword() throws Exception {
        Path custom = dir.resolve("custom.yaml");
        Files.writeString(custom, """
                server:
                  host: 127.0.0.1
                  port: 6380
                  max-clients: 256
                  timeout-seconds: 30
                  password: super-secret-password
                  tls:
                    enabled: true
                    cert-path: /tmp/server.crt
                    key-path: /tmp/server.key
                    ca-path: /tmp/ca.crt
                    require-client-auth: true
                """);
        TieringConfig config = TieringConfig.load(custom);
        assertThat(config.server().host()).isEqualTo("127.0.0.1");
        assertThat(config.server().port()).isEqualTo(6380);
        assertThat(config.server().maxClients()).isEqualTo(256);
        assertThat(config.server().timeoutSeconds()).isEqualTo(30);
        assertThat(config.server().password()).isEqualTo("super-secret-password");
        assertThat(config.server().tls().enabled()).isTrue();
        assertThat(config.server().tls().certPath()).isEqualTo("/tmp/server.crt");
        assertThat(config.server().tls().keyPath()).isEqualTo("/tmp/server.key");
        assertThat(config.server().tls().caPath()).isEqualTo("/tmp/ca.crt");
        assertThat(config.server().tls().requireClientAuth()).isTrue();
        assertThat(config.describe())
                .contains("server.auth=enabled", "server.tls=true")
                .doesNotContain("super-secret-password");
    }

    @Test
    void missingFileFallsBackToDefaults() {
        TieringConfig config = TieringConfig.load(dir.resolve("missing.yaml"));
        assertThat(config.server().port()).isEqualTo(6379);
        assertThat(config.tiering().highWatermark()).isEqualTo(0.85);
        config.validate();
    }

    @Test
    void loadsStorageAndVectorPathsAndValidatesWatermarks() throws Exception {
        Path custom = dir.resolve("paths.yaml");
        Files.writeString(custom, """
                storage:
                  data-dir: /var/lib/tiering
                  cold-dir: /mnt/cold
                vector:
                  index-dir: /var/lib/tiering/vec
                  max-level: 8
                  checkpoint-interval-ms: 15000
                tiering:
                  low-watermark: 0.60
                  high-watermark: 0.80
                  critical-watermark: 0.92
                  max-entry-count: 500000
                """);
        TieringConfig cfg = TieringConfig.load(custom);
        cfg.validate();
        assertThat(cfg.storage().dataDir()).isEqualTo("/var/lib/tiering");
        assertThat(cfg.storage().coldDir()).isEqualTo("/mnt/cold");
        assertThat(cfg.storage().walDir()).contains("wal");
        assertThat(cfg.vector().indexDir()).isEqualTo("/var/lib/tiering/vec");
        assertThat(cfg.vector().maxLevel()).isEqualTo(8);
        assertThat(cfg.tiering().maxEntryCount()).isEqualTo(500_000L);

        Path invalid = dir.resolve("invalid.yaml");
        Files.writeString(invalid, """
                tiering:
                  low-watermark: 0.90
                  high-watermark: 0.80
                  critical-watermark: 0.95
                """);
        TieringConfig bad = TieringConfig.load(invalid);
        assertThatThrownBy(bad::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("watermarks");
    }

    @Test
    void vectorCheckpointAndStorageRehydrateRoundTrip() throws Exception {
        MemTable mem = MemTable.create();
        VectorIndexStore store = new VectorIndexStore(6);
        VectorIndexSyncStorageEngine sync = new VectorIndexSyncStorageEngine(mem, store);

        sync.put("vec:1".getBytes(StandardCharsets.UTF_8),
                MultiModelCodec.encodeVector(new float[]{1.0f, 0.0f, 0.0f}));
        Path cp = dir.resolve("vector").resolve("default.vidx");
        Files.createDirectories(cp.getParent());
        store.checkpoint(cp);

        // 模拟 checkpoint 之后 WAL 恢复出一条新增向量
        mem.put("vec:2".getBytes(StandardCharsets.UTF_8),
                MultiModelCodec.encodeVector(new float[]{0.0f, 1.0f, 0.0f}));

        VectorIndexStore restored = VectorIndexStore.load(cp);
        assertThat(restored.size()).isEqualTo(1);
        VectorIndexSyncStorageEngine restoredSync = new VectorIndexSyncStorageEngine(mem, restored);
        int rehydrated = restoredSync.rehydrateFromStorage();
        assertThat(rehydrated).isEqualTo(2);
        assertThat(restored.size()).isEqualTo(2);
    }
}
