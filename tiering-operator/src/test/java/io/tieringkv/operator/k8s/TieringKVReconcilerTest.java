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
}
