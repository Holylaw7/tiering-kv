package io.tieringkv.deploy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kubernetes 集群内验证（ADR-0102）：仅 kind 环境（脚本 scripts/kind-e2e.sh）
 * 运行时执行，本地自动跳过。
 */
@Tag("container")
@EnabledIfEnvironmentVariable(named = "TIERINGKV_KIND_CLUSTER",
        matches = "true")
class KubernetesInClusterValidationTest {

    private static final Path BASE = Path.of("deploy", "kubernetes",
            "tiering-kv");

    @Test
    void clusterReadyFileExists() {
        assertThat(Files.exists(Path.of("target", "kind-cluster-ready")))
                .isTrue();
    }

    @Test
    void manifestsStillPresent() {
        for (String file : new String[]{"StatefulSet.yaml",
                "Service.yaml", "ConfigMap.yaml", "Secret.yaml",
                "PodDisruptionBudget.yaml", "Gateway.yaml"}) {
            assertThat(Files.exists(BASE.resolve(file)))
                    .as(file).isTrue();
        }
    }

    @Test
    void gatewaySmokeMarkerExists() {
        assertThat(Files.exists(Path.of("target", "kind-gateway-smoke")))
                .isTrue();
    }
}
