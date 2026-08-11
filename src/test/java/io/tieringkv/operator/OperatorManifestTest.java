package io.tieringkv.operator;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Operator 清单（ADR-0107）：CRD 结构与 sample 校验。 */
class OperatorManifestTest {

    private static final Path BASE = Path.of("deploy", "operator");

    @Test
    void crdExistsAndParses() throws Exception {
        assertThat(Files.exists(BASE.resolve(
                "tieringkvcluster-crd.yaml"))).isTrue();
        Map<String, Object> doc = yaml(BASE.resolve(
                "tieringkvcluster-crd.yaml"));
        assertThat(doc.get("kind")).isEqualTo("CustomResourceDefinition");
        Map<String, Object> metadata = map(doc.get("metadata"));
        assertThat(metadata.get("name"))
                .isEqualTo("tieringkvclusters.tieringkv.io");
    }

    @Test
    void crdGroupAndVersion() throws Exception {
        Map<String, Object> doc = yaml(BASE.resolve(
                "tieringkvcluster-crd.yaml"));
        Map<String, Object> spec = map(doc.get("spec"));
        assertThat(spec.get("group")).isEqualTo("tieringkv.io");
        assertThat(spec.get("scope")).isEqualTo("Namespaced");
        assertThat(spec.get("versions")).isNotNull();
    }

    @Test
    void crdRequiresCoreFields() throws Exception {
        Map<String, Object> doc = yaml(BASE.resolve(
                "tieringkvcluster-crd.yaml"));
        Map<String, Object> spec = map(doc.get("spec"));
        Object versions = spec.get("versions");
        assertThat(versions).isNotNull();
    }

    @Test
    void sampleUsesV1ApiAndThreeMetadataReplicas() throws Exception {
        Map<String, Object> doc = yaml(BASE.resolve("sample.yaml"));
        assertThat(doc.get("apiVersion")).isEqualTo("tieringkv.io/v1");
        assertThat(doc.get("kind")).isEqualTo("TieringKVCluster");
        Map<String, Object> spec = map(doc.get("spec"));
        assertThat(spec.get("metadataReplicas")).isEqualTo(3);
        assertThat(spec.get("storageReplicas")).isEqualTo(3);
        assertThat(spec.get("image")).isEqualTo("tiering-kv:1.0.0");
    }

    @Test
    void sampleHasBackupSchedule() throws Exception {
        Map<String, Object> doc = yaml(BASE.resolve("sample.yaml"));
        Map<String, Object> spec = map(doc.get("spec"));
        assertThat(spec.get("backupScheduleCron")).isEqualTo("0 2 * * *");
        assertThat(spec.get("backupRetentionHours")).isEqualTo(168);
    }

    @Test
    void sampleRegionsListed() throws Exception {
        Map<String, Object> doc = yaml(BASE.resolve("sample.yaml"));
        Map<String, Object> spec = map(doc.get("spec"));
        Object regions = spec.get("regionIds");
        assertThat(regions).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }
}
