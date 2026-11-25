package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.tieringkv.operator.OperatorAction;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v5.4.0 A1：Operator 核心修复、不可变字段安全 Patch、探针、真实备份 Job 与 CRD 契约验证。 */
@EnableKubernetesMockClient(crud = true)
class K8sActionApplierLifecycleTest {

    KubernetesClient kubernetesClient;

    @Test
    void storagePodUsesParticipantRoleAndAllTiersWireTcpProbes() {
        K8sTieringKVCluster cluster = cluster("prod", 3, 2, "ghcr.io/holylaw7/tiering-kv:v5.4.0");
        cluster.getSpec().setGatewayReplicas(2);
        cluster.getSpec().setMetadataStorageSize("2Gi");
        cluster.getSpec().setStorageDataSize("5Gi");
        cluster.getSpec().setStorageClassName("standard");

        K8sActionApplier applier = new K8sActionApplier(kubernetesClient);
        applier.apply(cluster, new OperatorAction(
                OperatorAction.ActionType.CREATE, "cluster", "create"));

        var metaSts = kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("prod-meta").get();
        var storageSts = kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("prod-storage").get();
        var gatewayDep = kubernetesClient.apps().deployments()
                .inNamespace("tiering-kv").withName("prod-gateway").get();
        var cm = kubernetesClient.configMaps()
                .inNamespace("tiering-kv").withName("prod-config").get();

        // 1. 修正核心启动 Bug：storage StatefulSet 必须注入 ROLE=participant
        var storageContainer = storageSts.getSpec().getTemplate().getSpec()
                .getContainers().get(0);
        assertThat(envValue(storageContainer.getEnv(), "ROLE"))
                .isEqualTo("participant");
        assertThat(cm.getData().get("start.sh")).contains("participant|storage)");

        // 2. 三层工作负载全部配置 TCP readinessProbe & livenessProbe
        var metaContainer = metaSts.getSpec().getTemplate().getSpec()
                .getContainers().get(0);
        var gatewayContainer = gatewayDep.getSpec().getTemplate().getSpec()
                .getContainers().get(0);
        assertThat(metaContainer.getReadinessProbe().getTcpSocket().getPort()
                .getStrVal()).isEqualTo("raft-rpc");
        assertThat(storageContainer.getReadinessProbe().getTcpSocket().getPort()
                .getStrVal()).isEqualTo("raft-rpc");
        assertThat(gatewayContainer.getReadinessProbe().getTcpSocket().getPort()
                .getStrVal()).isEqualTo("redis");
        assertThat(gatewayContainer.getLivenessProbe()).isNotNull();

        // 3. 可选规格透传生效：gatewayReplicas、PVC 大小与 storageClassName
        assertThat(gatewayDep.getSpec().getReplicas()).isEqualTo(2);
        var storagePvc = storageSts.getSpec().getVolumeClaimTemplates().get(0);
        assertThat(storagePvc.getSpec().getStorageClassName()).isEqualTo("standard");
        assertThat(storagePvc.getSpec().getResources().getRequests().get("storage")
                .toString()).isEqualTo("5Gi");

        // 4. Kind 注解与 CRD 对齐
        assertThat(HasMetadata.getKind(K8sTieringKVCluster.class))
                .isEqualTo("TieringKVCluster");
    }

    @Test
    void scaleAndUpgradePreserveImmutableStatefulSetPvcAndServiceClusterIp() {
        K8sTieringKVCluster cluster = cluster("prod", 3, 2, "tiering-kv:v1");
        K8sActionApplier applier = new K8sActionApplier(kubernetesClient);
        applier.apply(cluster, new OperatorAction(
                OperatorAction.ActionType.CREATE, "cluster", "create"));

        // 模拟真实 K8s 分配的不可变 Service clusterIP
        kubernetesClient.services().inNamespace("tiering-kv")
                .withName("prod-gateway").edit(svc -> {
                    svc.getSpec().setClusterIP("10.96.42.10");
                    return svc;
                });

        // 扩容 storage 2 -> 3（同时变更存储容量声明，验证安全 Patch 不会改写不可变 VCT）
        cluster.getSpec().setStorageReplicas(3);
        cluster.getSpec().setStorageDataSize("500Gi");
        cluster.getSpec().setImage("tiering-kv:v2");
        applier.apply(cluster, new OperatorAction(
                OperatorAction.ActionType.UPGRADE, "image", "upgrade"));

        var storageSts = kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("prod-storage").get();
        var gatewaySvc = kubernetesClient.services()
                .inNamespace("tiering-kv").withName("prod-gateway").get();
        var cm = kubernetesClient.configMaps()
                .inNamespace("tiering-kv").withName("prod-config").get();

        assertThat(storageSts.getSpec().getReplicas()).isEqualTo(3);
        assertThat(storageSts.getSpec().getTemplate().getSpec().getContainers()
                .get(0).getImage()).isEqualTo("tiering-kv:v2");
        // 不可变 volumeClaimTemplates 保持初建时的 100Gi 不被覆盖
        assertThat(storageSts.getSpec().getVolumeClaimTemplates().get(0)
                .getSpec().getResources().getRequests().get("storage")
                .toString()).isEqualTo("100Gi");
        // 扩容自动补全 r3 区域映射
        assertThat(cm.getData().get("regions.conf"))
                .contains("prod-storage-0 r1")
                .contains("prod-storage-1 r2")
                .contains("prod-storage-2 r3");
        // Service clusterIP 保持原值不被清空
        assertThat(gatewaySvc.getSpec().getClusterIP()).isEqualTo("10.96.42.10");
    }

    @Test
    void backupCronJobIsActiveAndOnDemandAnnotationCreatesAndObservesBackupJob() {
        K8sTieringKVCluster cluster = cluster("prod", 3, 2, "tiering-kv:v1");
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(K8sActionApplier.BACKUP_REQUEST_ANNOTATION, "e2e-001");
        cluster.getMetadata().setAnnotations(annotations);

        K8sActionApplier applier = new K8sActionApplier(kubernetesClient);
        applier.apply(cluster, new OperatorAction(
                OperatorAction.ActionType.CREATE, "cluster", "create"));

        var cronJob = kubernetesClient.batch().v1().cronjobs()
                .inNamespace("tiering-kv").withName("prod-backup").get();
        assertThat(cronJob).isNotNull();
        assertThat(cronJob.getSpec().getSuspend()).isFalse();
        String cronScript = cronJob.getSpec().getJobTemplate().getSpec()
                .getTemplate().getSpec().getContainers().get(0).getArgs().get(0);
        assertThat(cronScript).doesNotContain("WP-B").contains("PING").contains("BGSAVE");

        String expectedJobName = K8sActionApplier.backupJobName(cluster, "e2e-001");
        var job = kubernetesClient.batch().v1().jobs()
                .inNamespace("tiering-kv").withName(expectedJobName).get();
        assertThat(job).isNotNull();

        // 模拟 Job 执行完成并验证 observe() 回写备份状态
        JobStatus status = new JobStatus();
        status.setSucceeded(1);
        status.setCompletionTime("2026-11-25T13:00:00Z");
        job.setStatus(status);
        kubernetesClient.batch().v1().jobs().inNamespace("tiering-kv")
                .resource(job).replace();

        K8sTieringKVClusterStatus observed = applier.observe(cluster);
        assertThat(observed.getLastBackupJob()).isEqualTo(expectedJobName);
        assertThat(observed.getLastBackupStatus()).isEqualTo("SUCCEEDED");
        assertThat(observed.getLastBackupTime()).isEqualTo("2026-11-25T13:00:00Z");

        // 删除集群同时清理备份 Job
        applier.apply(cluster, new OperatorAction(
                OperatorAction.ActionType.DELETE, "cluster", "delete"));
        assertThat(kubernetesClient.batch().v1().jobs()
                .inNamespace("tiering-kv").list().getItems()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void crdDeclaresStatusSubresourceAndPhaseColumns() throws Exception {
        Map<String, Object> crd;
        try (var reader = Files.newBufferedReader(
                Path.of("deploy", "operator", "tieringkvcluster-crd.yaml"))) {
            crd = (Map<String, Object>) new Yaml().load(reader);
        }
        Map<String, Object> spec = (Map<String, Object>) crd.get("spec");
        List<Map<String, Object>> versions =
                (List<Map<String, Object>>) spec.get("versions");
        Map<String, Object> v1 = versions.get(0);
        Map<String, Object> subresources =
                (Map<String, Object>) v1.get("subresources");
        assertThat(subresources).containsKey("status");
        List<Map<String, Object>> columns =
                (List<Map<String, Object>>) v1.get("additionalPrinterColumns");
        assertThat(columns).extracting(c -> c.get("name"))
                .contains("Phase", "Meta", "Storage", "Gateway", "Image");

        Map<String, Object> schema = (Map<String, Object>) v1.get("schema");
        Map<String, Object> openApi =
                (Map<String, Object>) schema.get("openAPIV3Schema");
        Map<String, Object> props =
                (Map<String, Object>) openApi.get("properties");
        Map<String, Object> statusProps = (Map<String, Object>)
                ((Map<String, Object>) props.get("status")).get("properties");
        assertThat(statusProps).containsKeys(
                "phase", "lastBackupJob", "lastBackupStatus", "lastBackupTime", "message");
    }

    private static String envValue(List<EnvVar> envVars, String name) {
        return envVars.stream()
                .filter(e -> name.equals(e.getName()))
                .map(EnvVar::getValue)
                .findFirst()
                .orElse(null);
    }

    private static K8sTieringKVCluster cluster(String name,
                                               int metadataReplicas,
                                               int storageReplicas,
                                               String image) {
        K8sTieringKVCluster cluster = new K8sTieringKVCluster();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace("tiering-kv");
        metadata.setUid("uid-" + name);
        metadata.setGeneration(1L);
        cluster.setMetadata(metadata);
        K8sTieringKVClusterSpec spec = new K8sTieringKVClusterSpec();
        spec.setMetadataReplicas(metadataReplicas);
        spec.setStorageReplicas(storageReplicas);
        spec.setRegionIds(List.of("r1", "r2"));
        spec.setImage(image);
        spec.setBackupScheduleCron("0 2 * * *");
        spec.setBackupRetentionHours(168);
        cluster.setSpec(spec);
        return cluster;
    }
}
