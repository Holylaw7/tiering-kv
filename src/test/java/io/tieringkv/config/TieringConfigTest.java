package io.tieringkv.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(config.describe()).contains("server.port=6379", "server.max-clients=10000");
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
    }
}
