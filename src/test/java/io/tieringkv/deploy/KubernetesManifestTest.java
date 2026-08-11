package io.tieringkv.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kubernetes 生产清单校验（ADR-0096/0098）：结构、副本、端口、PDB。 */
class KubernetesManifestTest {

    private static final Path BASE = Path.of("deploy", "kubernetes",
            "tiering-kv");
    private static final String[] FILES = {
            "ConfigMap.yaml", "Secret.yaml", "Service.yaml",
            "StatefulSet.yaml", "Gateway.yaml", "PodDisruptionBudget.yaml"
    };

    @Test
    void allManifestsExist() {
        for (String file : FILES) {
            assertThat(Files.exists(BASE.resolve(file)))
                    .as(file).isTrue();
        }
    }

    @Test
    void allManifestsParseAsYaml() throws Exception {
        for (String file : FILES) {
            assertThat(documents(BASE.resolve(file))).isNotEmpty();
        }
    }

    @Test
    void metadataStatefulSetHasThreeReplicas() throws Exception {
        Map<String, Object> statefulSet = find("StatefulSet.yaml",
                "StatefulSet", "tiering-kv-meta");
        assertThat(replicas(statefulSet)).isEqualTo(3);
        assertThat(spec(statefulSet).get("serviceName"))
                .isEqualTo("tiering-kv-meta");
    }

    @Test
    void storageStatefulSetHasThreeReplicas() throws Exception {
        Map<String, Object> statefulSet = find("StatefulSet.yaml",
                "StatefulSet", "tiering-kv-storage");
        assertThat(replicas(statefulSet)).isEqualTo(3);
        assertThat(spec(statefulSet).get("serviceName"))
                .isEqualTo("tiering-kv-storage");
    }

    @Test
    void metadataServiceHeadlessWithRaftPort() throws Exception {
        Map<String, Object> service = find("Service.yaml",
                "Service", "tiering-kv-meta");
        assertThat(spec(service).get("clusterIP")).isEqualTo("None");
        assertThat(ports(spec(service))).anyMatch(port ->
                ((Number) port.get("port")).intValue() == 7300);
    }

    @Test
    void gatewayServiceExposesRedisPort() throws Exception {
        Map<String, Object> service = find("Service.yaml",
                "Service", "tiering-kv-gateway");
        assertThat(ports(spec(service))).anyMatch(port ->
                ((Number) port.get("port")).intValue() == 6379);
    }

    @Test
    void configMapContainsRuntimeFiles() throws Exception {
        Map<String, Object> configMap = find("ConfigMap.yaml",
                "ConfigMap", "tiering-kv-config");
        Map<String, Object> data = data(configMap);
        assertThat(data).containsKeys("start.sh", "regions.conf",
                "tiering-kv.yaml");
        assertThat((String) data.get("start.sh")).contains("metadata");
        assertThat((String) data.get("regions.conf"))
                .contains("tiering-kv-storage-0 r1");
    }

    @Test
    void secretContainsAuthKeys() throws Exception {
        Map<String, Object> secret = find("Secret.yaml",
                "Secret", "tiering-kv-secrets");
        assertThat(stringData(secret)).containsKeys("admin-password",
                "cluster-auth-token", "backup-encryption-key");
    }

    @Test
    void metadataPdbKeepsQuorum() throws Exception {
        Map<String, Object> pdb = find("PodDisruptionBudget.yaml",
                "PodDisruptionBudget", "tiering-kv-meta-pdb");
        assertThat(((Number) spec(pdb).get("minAvailable")).intValue())
                .isEqualTo(2);
    }

    @Test
    void gatewayDeploymentPointsToThreeStoragePods() throws Exception {
        Map<String, Object> deployment = find("Gateway.yaml",
                "Deployment", "tiering-kv-gateway");
        List<Map<String, Object>> containers = containers(deployment);
        List<Map<String, Object>> env = env(containers.get(0));
        String regions = env.stream()
                .filter(entry -> "REGIONS".equals(entry.get("name")))
                .map(entry -> (String) entry.get("value"))
                .findFirst().orElse("");
        assertThat(regions)
                .contains("tiering-kv-storage-0.tiering-kv-storage")
                .contains("tiering-kv-storage-1.tiering-kv-storage")
                .contains("tiering-kv-storage-2.tiering-kv-storage");
    }

    private static int replicas(Map<String, Object> workload) {
        return ((Number) spec(workload).get("replicas")).intValue();
    }

    private static Map<String, Object> find(String file, String kind,
                                            String name) throws Exception {
        return documents(BASE.resolve(file)).stream()
                .filter(document -> kind.equals(document.get("kind")))
                .filter(document -> name.equals(metadata(document)
                        .get("name")))
                .findFirst().orElseThrow();
    }

    private static List<Map<String, Object>> documents(Path file)
            throws Exception {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            for (Object document : new Yaml().loadAll(reader)) {
                if (document != null) {
                    docs.add((Map<String, Object>) document);
                }
            }
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> document) {
        return (Map<String, Object>) document.get("metadata");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(Map<String, Object> document) {
        return (Map<String, Object>) document.get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> document) {
        return (Map<String, Object>) document.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringData(
            Map<String, Object> document) {
        return (Map<String, Object>) document.get("stringData");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ports(Map<String, Object> spec) {
        return (List<Map<String, Object>>) spec.get("ports");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> containers(
            Map<String, Object> workload) {
        Map<String, Object> template = (Map<String, Object>)
                spec(workload).get("template");
        Map<String, Object> pod = (Map<String, Object>) template.get("spec");
        return (List<Map<String, Object>>) pod.get("containers");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> env(Map<String, Object> container) {
        return (List<Map<String, Object>>) container.get("env");
    }
}
