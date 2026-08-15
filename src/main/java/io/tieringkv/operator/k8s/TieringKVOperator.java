package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.concurrent.CountDownLatch;

/**
 * Tiering-KV Operator（ADR-0322 M4 增强）：Watch TieringKVCluster →
 * reconcile → 动作执行 → 状态回写。
 *
 * <p>入口：main 运行 watch 循环（Ctrl-C 退出）；测试可通过
 * {@link #reconcileNow} 同步驱动。
 */
public final class TieringKVOperator implements AutoCloseable {

    private final KubernetesClient kubernetesClient;
    private final OperatorClient client;
    private final TieringKVReconciler reconciler;
    private final ActionApplier applier;
    private SharedIndexInformer<K8sTieringKVCluster> informer;
    private final CountDownLatch stopped = new CountDownLatch(1);

    public TieringKVOperator(KubernetesClient kubernetesClient,
                             OperatorClient client,
                             ActionApplier applier) {
        if (kubernetesClient == null || client == null
                || applier == null) {
            throw new IllegalArgumentException(
                    "kubernetesClient, client and applier required");
        }
        this.kubernetesClient = kubernetesClient;
        this.client = client;
        this.applier = applier;
        this.reconciler = new TieringKVReconciler();
    }

    /** 启动 Watch + reconcile 循环（非阻塞）。 */
    public void start() {
        informer = kubernetesClient.resources(K8sTieringKVCluster.class)
                .inAnyNamespace()
                .inform(new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(K8sTieringKVCluster resource) {
                        reconcileNow(resource);
                    }

                    @Override
                    public void onUpdate(K8sTieringKVCluster old,
                                         K8sTieringKVCluster updated) {
                        reconcileNow(updated);
                    }

                    @Override
                    public void onDelete(K8sTieringKVCluster resource,
                                         boolean deletedFinalStateUnknown) {
                        // 资源删除：清理由 GC 负责
                    }
                });
    }

    /** 同步 reconcile：计划 → 执行动作 → 状态回写。 */
    public K8sTieringKVCluster reconcileNow(
            K8sTieringKVCluster resource) {
        TieringKVReconciler.ReconcileResult result =
                reconciler.reconcile(resource);
        for (io.tieringkv.operator.OperatorAction action :
                result.actions()) {
            applier.apply(action);
        }
        resource.setStatus(result.status());
        client.updateStatus(resource);
        return resource;
    }

    public void awaitStopped() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (informer != null) {
            informer.close();
        }
        stopped.countDown();
    }

    public static void main(String[] args) throws Exception {
        KubernetesClient kubernetesClient =
                new KubernetesClientBuilder().build();
        TieringKVOperator operator = new TieringKVOperator(
                kubernetesClient,
                new Fabric8OperatorClient(kubernetesClient),
                new LoggingActionApplier());
        try (operator) {
            operator.start();
            System.out.println(
                    "Tiering-KV Operator watching tieringkvclusters");
            operator.awaitStopped();
        } finally {
            kubernetesClient.close();
        }
    }
}
