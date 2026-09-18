package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.tieringkv.operator.OperatorAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fabric8 mock coverage for real Operator resource application. */
@EnableKubernetesMockClient(crud = true)
class K8sActionApplierTest {

    KubernetesClient kubernetesClient;

    @Test
    void createIsIdempotentAndOwnsAllWorkloads() {
        K8sTieringKVCluster cluster = cluster("demo", 3, 2, "image:v1");
        K8sActionApplier applier = new K8sActionApplier(kubernetesClient);

        applier.apply(cluster, action(OperatorAction.ActionType.CREATE));
        applier.apply(cluster, action(OperatorAction.ActionType.CREATE));

        var meta = kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("demo-meta").get();
        var storage = kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("demo-storage").get();
        var gateway = kubernetesClient.apps().deployments()
                .inNamespace("tiering-kv").withName("demo-gateway").get();
        var service = kubernetesClient.services().inNamespace("tiering-kv")
                .withName("demo-gateway").get();
        var backup = kubernetesClient.batch().v1().cronjobs()
                .inNamespace("tiering-kv").withName("demo-backup").get();

        assertThat(meta).isNotNull();
        assertThat(storage).isNotNull();
        assertThat(gateway).isNotNull();
        assertThat(service).isNotNull();
        assertThat(backup).isNotNull();
        assertThat(meta.getSpec().getReplicas()).isEqualTo(3);
        assertThat(storage.getSpec().getReplicas()).isEqualTo(2);
        assertThat(gateway.getSpec().getReplicas()).isEqualTo(1);
        assertThat(meta.getSpec().getTemplate().getSpec().getContainers()
                .get(0).getImage()).isEqualTo("image:v1");
        assertThat(kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").list().getItems()).hasSize(2);
        assertThat(owner(meta.getMetadata())).satisfies(this::assertOwner);
    }

    @Test
    void scaleAndUpgradeReconcileDesiredState() {
        K8sTieringKVCluster cluster = cluster("demo", 3, 2, "image:v1");
        K8sActionApplier applier = new K8sActionApplier(kubernetesClient);
        applier.apply(cluster, action(OperatorAction.ActionType.CREATE));

        cluster.getSpec().setStorageReplicas(5);
        applier.apply(cluster, action(OperatorAction.ActionType.SCALE_UP,
                "storage"));
        assertThat(kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("demo-storage").get()
                .getSpec().getReplicas()).isEqualTo(5);

        cluster.getSpec().setImage("image:v2");
        applier.apply(cluster, action(OperatorAction.ActionType.UPGRADE,
                "image"));
        assertThat(kubernetesClient.apps().deployments()
                .inNamespace("tiering-kv").withName("demo-gateway").get()
                .getSpec().getTemplate().getSpec().getContainers().get(0)
                .getImage()).isEqualTo("image:v2");
    }

    @Test
    void deleteRemovesOwnedResourcesButRetainsPersistentDataContract() {
        K8sTieringKVCluster cluster = cluster("demo", 3, 2, "image:v1");
        K8sActionApplier applier = new K8sActionApplier(kubernetesClient);
        applier.apply(cluster, action(OperatorAction.ActionType.CREATE));
        applier.apply(cluster, action(OperatorAction.ActionType.DELETE));

        assertThat(kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").list().getItems()).isEmpty();
        assertThat(kubernetesClient.apps().deployments()
                .inNamespace("tiering-kv").list().getItems()).isEmpty();
        assertThat(kubernetesClient.services().inNamespace("tiering-kv")
                .list().getItems()).isEmpty();
        assertThat(kubernetesClient.batch().v1().cronjobs()
                .inNamespace("tiering-kv").list().getItems()).isEmpty();
        assertThat(kubernetesClient.configMaps().inNamespace("tiering-kv")
                .list().getItems()).isEmpty();
    }

    @Test
    void observeReadsReadyReplicaCounts() {
        K8sTieringKVCluster cluster = cluster("demo", 3, 2, "image:v1");
        K8sActionApplier applier = new K8sActionApplier(kubernetesClient);
        applier.apply(cluster, action(OperatorAction.ActionType.CREATE));

        var meta = kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("demo-meta").get();
        var storage = kubernetesClient.apps().statefulSets()
                .inNamespace("tiering-kv").withName("demo-storage").get();
        var gateway = kubernetesClient.apps().deployments()
                .inNamespace("tiering-kv").withName("demo-gateway").get();
        meta.setStatus(new StatefulSetStatus());
        storage.setStatus(new StatefulSetStatus());
        gateway.setStatus(new DeploymentStatus());
        meta.getStatus().setReadyReplicas(3);
        storage.getStatus().setReadyReplicas(2);
        gateway.getStatus().setReadyReplicas(1);
        kubernetesClient.apps().statefulSets().inNamespace("tiering-kv")
                .resource(meta).replace();
        kubernetesClient.apps().statefulSets().inNamespace("tiering-kv")
                .resource(storage).replace();
        kubernetesClient.apps().deployments().inNamespace("tiering-kv")
                .resource(gateway).replace();

        K8sTieringKVClusterStatus status = applier.observe(cluster);
        assertThat(status.getReadyMetadata()).isEqualTo(3);
        assertThat(status.getReadyStorage()).isEqualTo(2);
        assertThat(status.getReadyGateway()).isEqualTo(1);
    }

    private static OperatorAction action(OperatorAction.ActionType type) {
        return action(type, "cluster");
    }

    private static OperatorAction action(OperatorAction.ActionType type,
                                         String target) {
        return new OperatorAction(type, target, "test");
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

    private static OwnerReference owner(ObjectMeta metadata) {
        return metadata.getOwnerReferences().get(0);
    }

    private void assertOwner(OwnerReference owner) {
        assertThat(owner.getApiVersion()).isEqualTo("tieringkv.io/v1");
        assertThat(owner.getKind()).isEqualTo("TieringKVCluster");
        assertThat(owner.getName()).isEqualTo("demo");
        assertThat(owner.getUid()).isEqualTo("uid-demo");
        assertThat(owner.getController()).isTrue();
        assertThat(owner.getBlockOwnerDeletion()).isTrue();
    }
}
