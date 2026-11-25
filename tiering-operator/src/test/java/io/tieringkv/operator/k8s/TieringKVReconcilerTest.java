package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** K8s reconcile 核心（ADR-0322 M4 增强）：纯逻辑状态推进。 */
class TieringKVReconcilerTest {

    private static K8sTieringKVCluster cluster(String name,
                                               int metadataReplicas,
                                               int storageReplicas,
                                               String image) {
        K8sTieringKVCluster cluster = new K8sTieringKVCluster();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setGeneration(1L);
        cluster.setMetadata(meta);
        K8sTieringKVClusterSpec spec = new K8sTieringKVClusterSpec();
        spec.setMetadataReplicas(metadataReplicas);
        spec.setStorageReplicas(storageReplicas);
        spec.setRegionIds(List.of("r1"));
        spec.setImage(image);
        cluster.setSpec(spec);
        return cluster;
    }

    private static void setReady(K8sTieringKVCluster cluster,
                                 int metadata, int storage, int gateway,
                                 long observedGeneration) {
        K8sTieringKVClusterStatus status =
                new K8sTieringKVClusterStatus();
        status.setReadyMetadata(metadata);
        status.setReadyStorage(storage);
        status.setReadyGateway(gateway);
        status.setObservedGeneration(observedGeneration);
        cluster.setStatus(status);
    }

    @Test
    void firstReconcileEntersProvisioning() {
        TieringKVReconciler.ReconcileResult result =
                new TieringKVReconciler().reconcile(
                        cluster("t", 3, 3, "img:v1"));
        assertThat(result.status().getPhase())
                .isEqualTo("PROVISIONING");
        assertThat(result.status().getObservedGeneration())
                .isEqualTo(1);
        assertThat(result.actions()).isNotEmpty();
    }

    @Test
    void readyWhenAllReplicasObserved() {
        TieringKVReconciler reconciler = new TieringKVReconciler();
        K8sTieringKVCluster cluster = cluster("t", 3, 3, "img:v1");
        reconciler.reconcile(cluster);
        setReady(cluster, 3, 3, 1, 1);
        TieringKVReconciler.ReconcileResult result =
                reconciler.reconcile(cluster);
        assertThat(result.status().getPhase())
                .isEqualTo("READY");
    }

    @Test
    void upgradingWhenGenerationAdvances() {
        TieringKVReconciler reconciler = new TieringKVReconciler();
        K8sTieringKVCluster cluster = cluster("t", 3, 3, "img:v1");
        reconciler.reconcile(cluster);
        setReady(cluster, 3, 3, 1, 1);
        reconciler.reconcile(cluster);

        // 新 spec 提交：generation 前进但状态未观测 → UPGRADING
        cluster.getMetadata().setGeneration(2L);
        TieringKVReconciler.ReconcileResult result =
                reconciler.reconcile(cluster);
        assertThat(result.status().getPhase())
                .isEqualTo("UPGRADING");
        assertThat(result.status().getLastAction())
                .contains("UPGRADE");
    }

    @Test
    void returnsToReadyAfterUpgradeObserved() {
        TieringKVReconciler reconciler = new TieringKVReconciler();
        K8sTieringKVCluster cluster = cluster("t", 3, 3, "img:v1");
        reconciler.reconcile(cluster);
        setReady(cluster, 3, 3, 1, 1);
        reconciler.reconcile(cluster);
        cluster.getMetadata().setGeneration(2L);
        reconciler.reconcile(cluster);
        setReady(cluster, 3, 3, 1, 2);
        TieringKVReconciler.ReconcileResult result =
                reconciler.reconcile(cluster);
        assertThat(result.status().getPhase())
                .isEqualTo("READY");
    }

    @Test
    void missingMetadataUsesDefaultName() {
        K8sTieringKVCluster cluster = new K8sTieringKVCluster();
        cluster.setSpec(cluster("t", 3, 3, "img:v1").getSpec());
        TieringKVReconciler.ReconcileResult result =
                new TieringKVReconciler().reconcile(cluster);
        assertThat(result.status().getPhase())
                .isEqualTo("PROVISIONING");
    }

    @Test
    void scalingStaysUpgradingUntilAllTargetReplicasBecomeReady() {
        TieringKVReconciler reconciler = new TieringKVReconciler();
        K8sTieringKVCluster cluster = cluster("scale-test", 3, 2, "img:v1");
        reconciler.reconcile(cluster);
        setReady(cluster, 3, 2, 1, 1);
        assertThat(reconciler.reconcile(cluster).status().getPhase())
                .isEqualTo("READY");

        // 扩容 storage 2 -> 3，generation=2 且仍仅 2 副本就绪：必须停留在 UPGRADING
        cluster.getMetadata().setGeneration(2L);
        cluster.getSpec().setStorageReplicas(3);
        setReady(cluster, 3, 2, 1, 2);
        assertThat(reconciler.reconcile(cluster).status().getPhase())
                .isEqualTo("UPGRADING");

        // 新 Pod 就绪（3/3）后收敛回 READY
        setReady(cluster, 3, 3, 1, 2);
        assertThat(reconciler.reconcile(cluster).status().getPhase())
                .isEqualTo("READY");
    }

    @Test
    void onDemandBackupAnnotationTriggersBackupOnceWithoutCronSchedule() {
        TieringKVReconciler reconciler = new TieringKVReconciler();
        K8sTieringKVCluster cluster = cluster("bkp-test", 3, 2, "img:v1");
        cluster.getMetadata().setAnnotations(java.util.Map.of(
                K8sActionApplier.BACKUP_REQUEST_ANNOTATION, "manual-42"));
        setReady(cluster, 3, 2, 1, 1);

        TieringKVReconciler.ReconcileResult first = reconciler.reconcile(cluster);
        assertThat(first.actions()).anyMatch(a ->
                a.type() == io.tieringkv.operator.OperatorAction.ActionType.TRIGGER_BACKUP
                        && a.target().contains("manual-42"));

        // Job 创建并观测回写后，不再重复触发备份
        cluster.getStatus().setLastBackupJob(
                K8sActionApplier.backupJobName(cluster, "manual-42"));
        TieringKVReconciler.ReconcileResult second = reconciler.reconcile(cluster);
        assertThat(second.actions()).noneMatch(a ->
                a.type() == io.tieringkv.operator.OperatorAction.ActionType.TRIGGER_BACKUP);
    }
}
