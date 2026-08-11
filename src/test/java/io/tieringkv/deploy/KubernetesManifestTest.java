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

    @Test
    void metadataContainerEnvSetsRoleAndDataDir() throws Exception {
        Map<String, Object> statefulSet = find("StatefulSet.yaml",
                "StatefulSet", "tiering-kv-meta");
        List<Map<String, Object>> containers = containers(statefulSet);
        assertThat(envValue(containers.get(0), "ROLE"))
                .isEqualTo("metadata");
        assertThat(envValue(containers.get(0), "DATA_DIR"))
                .isEqualTo("/data");
        assertThat(envValue(containers.get(0), "RPC_PORT"))
                .isEqualTo("7300");
    }

    @Test
    void storageContainerEnvSetsParticipantRole() throws Exception {
        Map<String, Object> statefulSet = find("StatefulSet.yaml",
                "StatefulSet", "tiering-kv-storage");
        List<Map<String, Object>> containers = containers(statefulSet);
        assertThat(envValue(containers.get(0), "ROLE"))
                .isEqualTo("participant");
        assertThat(envValue(containers.get(0), "RPC_PORT"))
                .isEqualTo("7100");
    }

    @Test
    void gatewayContainerEnvPointsToMetadataService() throws Exception {
        Map<String, Object> deployment = find("Gateway.yaml",
                "Deployment", "tiering-kv-gateway");
        List<Map<String, Object>> containers = containers(deployment);
        assertThat(envValue(containers.get(0), "ROLE"))
                .isEqualTo("gateway");
        assertThat(envValue(containers.get(0), "METADATA_SERVICE"))
                .isEqualTo("tiering-kv-meta");
        assertThat(envValue(containers.get(0), "GATEWAY_PORT"))
                .isEqualTo("6379");
    }

    @Test
    void bothStatefulSetsHavePvcTemplates() throws Exception {
        for (String name : List.of("tiering-kv-meta",
                "tiering-kv-storage")) {
            Map<String, Object> statefulSet = find("StatefulSet.yaml",
                    "StatefulSet", name);
            assertThat(volumeClaimTemplates(statefulSet)).hasSize(1);
            Map<String, Object> claim = volumeClaimTemplates(statefulSet)
                    .get(0);
            assertThat(metadata(claim).get("name")).isEqualTo("data");
        }
    }

    @Test
    void storagePdbAlsoKeepsQuorum() throws Exception {
        Map<String, Object> pdb = find("PodDisruptionBudget.yaml",
                "PodDisruptionBudget", "tiering-kv-storage-pdb");
        assertThat(((Number) spec(pdb).get("minAvailable")).intValue())
                .isEqualTo(2);
    }

    @Test
    void gatewayDeploymentHasTwoReplicas() throws Exception {
        Map<String, Object> deployment = find("Gateway.yaml",
                "Deployment", "tiering-kv-gateway");
        assertThat(replicas(deployment)).isEqualTo(2);
    }

    @Test
    void startScriptSupportsAllRoles() throws Exception {
        Map<String, Object> configMap = find("ConfigMap.yaml",
                "ConfigMap", "tiering-kv-config");
        String script = (String) data(configMap).get("start.sh");
        for (String role : List.of("metadata", "participant",
                "coordinator", "gateway")) {
            assertThat(script).contains(role);
        }
    }

    @Test
    void statefulSetsGracefulTermination() throws Exception {
        for (String name : List.of("tiering-kv-meta",
                "tiering-kv-storage")) {
            Map<String, Object> statefulSet = find("StatefulSet.yaml",
                    "StatefulSet", name);
            assertThat(terminationGracePeriodSeconds(statefulSet))
                    .isEqualTo(60);
        }
    }

    @Test
    void gatewayGracefulTermination() throws Exception {
        Map<String, Object> deployment = find("Gateway.yaml",
                "Deployment", "tiering-kv-gateway");
        assertThat(terminationGracePeriodSeconds(deployment))
                .isEqualTo(30);
    }

    @Test
    void serviceSelectorsMatchWorkloadTiers() throws Exception {
        Map<String, Object> metaService = find("Service.yaml",
                "Service", "tiering-kv-meta");
        assertThat(selector(spec(metaService)))
                .containsEntry("tier", "metadata");
        Map<String, Object> gatewayService = find("Service.yaml",
                "Service", "tiering-kv-gateway");
        assertThat(selector(spec(gatewayService)))
                .containsEntry("tier", "gateway");
    }

    @Test
    void secretIsOpaqueType() throws Exception {
        Map<String, Object> secret = find("Secret.yaml",
                "Secret", "tiering-kv-secrets");
        assertThat(secret.get("type")).isEqualTo("Opaque");
    }

    @Test
    void statefulSetsDeclareResourceLimits() throws Exception {
        for (String name : List.of("tiering-kv-meta",
                "tiering-kv-storage")) {
            Map<String, Object> statefulSet = find("StatefulSet.yaml",
                    "StatefulSet", name);
            Map<String, Object> resources = resources(
                    containers(statefulSet).get(0));
            assertThat(resources).containsKey("limits");
            assertThat(resources).containsKey("requests");
        }
    }

    @Test
    void gatewayRegionsUseHeadlessDnsNames() throws Exception {
        Map<String, Object> deployment = find("Gateway.yaml",
                "Deployment", "tiering-kv-gateway");
        String regions = envValue(containers(deployment).get(0),
                "REGIONS");
        assertThat(regions)
                .contains("r1@tiering-kv-storage-0.tiering-kv-storage:7100")
                .contains("r2@tiering-kv-storage-1.tiering-kv-storage:7100")
                .contains("r3@tiering-kv-storage-2.tiering-kv-storage:7100");
    }

    @Test
    void pdbFileContainsBothBudgets() throws Exception {
        List<Map<String, Object>> docs = documents(
                BASE.resolve("PodDisruptionBudget.yaml"));
        assertThat(docs).hasSize(2);
        assertThat(docs).allMatch(document ->
                "PodDisruptionBudget".equals(document.get("kind")));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> selector(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("selector");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podSpec(
            Map<String, Object> workload) {
        Map<String, Object> template = (Map<String, Object>)
                spec(workload).get("template");
        return (Map<String, Object>) template.get("spec");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> volumeClaimTemplates(
            Map<String, Object> workload) {
        return (List<Map<String, Object>>) spec(workload)
                .get("volumeClaimTemplates");
    }

    private static long terminationGracePeriodSeconds(
            Map<String, Object> workload) {
        return ((Number) podSpec(workload)
                .get("terminationGracePeriodSeconds")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resources(
            Map<String, Object> container) {
        return (Map<String, Object>) container.get("resources");
    }

    private static String envValue(Map<String, Object> container,
                                   String name) {
        return env(container).stream()
                .filter(entry -> name.equals(entry.get("name")))
                .map(entry -> (String) entry.get("value"))
                .findFirst().orElse("");
    }
}
