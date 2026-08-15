package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.tieringkv.operator.OperatorAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Operator reconcile 流程（ADR-0322 M4 增强）：动作执行 + 状态回写。 */
@EnableKubernetesMockClient
class TieringKVOperatorTest {

    KubernetesClient kubernetesClient;

    private static K8sTieringKVCluster sampleCluster() {
        K8sTieringKVCluster cluster = new K8sTieringKVCluster();
        ObjectMeta meta = new ObjectMeta();
        meta.setName("test-cluster");
        meta.setNamespace("tiering-kv");
        meta.setGeneration(1L);
        cluster.setMetadata(meta);
        K8sTieringKVClusterSpec spec = new K8sTieringKVClusterSpec();
        spec.setMetadataReplicas(3);
        spec.setStorageReplicas(3);
        spec.setRegionIds(List.of("r1", "r2"));
        spec.setImage("tiering-kv:v1");
        cluster.setSpec(spec);
        return cluster;
    }

    /** 内存 fake：记录 upsert/updateStatus 的 resource。 */
    private static final class FakeClient implements OperatorClient {
        private final List<K8sTieringKVCluster> upserts =
                new ArrayList<>();
        private final List<K8sTieringKVCluster> statusWrites =
                new ArrayList<>();

        @Override
        public void upsert(K8sTieringKVCluster resource) {
            upserts.add(resource);
        }

        @Override
        public void updateStatus(K8sTieringKVCluster resource) {
            statusWrites.add(resource);
        }
    }

    @Test
    void reconcileExecutesActionsAndWritesStatus() {
        FakeClient client = new FakeClient();
        List<OperatorAction> actions = new ArrayList<>();
        TieringKVOperator operator =
                new TieringKVOperator(kubernetesClient, client,
                        actions::add);

        K8sTieringKVCluster cluster = sampleCluster();
        K8sTieringKVCluster updated = operator.reconcileNow(cluster);

        assertThat(updated.getStatus().getPhase())
                .isEqualTo("PROVISIONING");
        assertThat(updated.getStatus().getObservedGeneration())
                .isEqualTo(1);
        assertThat(actions).isNotEmpty();
        assertThat(client.statusWrites).hasSize(1);
        assertThat(client.statusWrites.get(0).getStatus()
                .getPhase()).isEqualTo("PROVISIONING");
    }

    @Test
    void secondReconcileObservesReady() {
        FakeClient client = new FakeClient();
        TieringKVOperator operator =
                new TieringKVOperator(kubernetesClient, client,
                        action -> {
                });
        K8sTieringKVCluster cluster = sampleCluster();
        operator.reconcileNow(cluster);

        K8sTieringKVClusterStatus ready =
                new K8sTieringKVClusterStatus();
        ready.setReadyMetadata(3);
        ready.setReadyStorage(3);
        ready.setReadyGateway(1);
        ready.setObservedGeneration(1);
        cluster.setStatus(ready);
        K8sTieringKVCluster updated = operator.reconcileNow(cluster);
        assertThat(updated.getStatus().getPhase())
                .isEqualTo("READY");
        assertThat(client.upserts).isEmpty(); // 状态推进不触发 upsert
    }
}
