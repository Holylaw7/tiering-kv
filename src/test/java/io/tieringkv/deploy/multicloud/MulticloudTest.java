package io.tieringkv.deploy.multicloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多云部署与迁移（ADR-0136）。 */
class MulticloudTest {

    @Test
    void configFields() {
        MulticloudConfig config = new MulticloudConfig(
                "gp2", "nginx", "ghcr.io/holylaw7/tiering-kv", 2);
        assertThat(config.storageClass()).isEqualTo("gp2");
        assertThat(config.ingressClass()).isEqualTo("nginx");
        assertThat(config.registry()).isEqualTo("ghcr.io/holylaw7/tiering-kv");
        assertThat(config.gatewayReplicas()).isEqualTo(2);
    }

    @Test
    void blankStorageClassRejected() {
        assertThatThrownBy(() -> new MulticloudConfig(
                " ", "nginx", "registry", 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "class {0}")
    @ValueSource(strings = {"gp2", "standard", "managed-csi"})
    void parameterizedStorageClasses(String storageClass) {
        assertThat(new MulticloudConfig(storageClass, "nginx",
                "registry", 2).storageClass())
                .isEqualTo(storageClass);
    }

    @Test
    void cloudMigrationMovesAll() {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            source.put("k" + i, bytes("v" + i));
        }
        CloudMigration migration = new CloudMigration(source, target);
        assertThat(migration.migrate()).isEqualTo(100);
        assertThat(migration.verify()).isTrue();
        assertThat(target).hasSize(100);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 50, 500})
    void parameterizedCloudMigration(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v"));
        }
        CloudMigration migration = new CloudMigration(source, target);
        migration.migrate();
        assertThat(migration.verify()).isTrue();
    }

    @Test
    void cloudMigrationVerifyFailsIfSourceRemains() {
        Map<String, byte[]> source = new LinkedHashMap<>();
        source.put("k", bytes("v"));
        assertThat(new CloudMigration(source, new LinkedHashMap<>())
                .verify()).isFalse();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
