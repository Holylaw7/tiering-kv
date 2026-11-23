package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tiering-KV Operator（ADR-0322 M4 增强）：Watch TieringKVCluster →
 * reconcile → 动作执行 → 状态回写。
 *
 * <p>入口：main 运行 watch 循环（Ctrl-C 退出）；测试可通过
 * {@link #reconcileNow} 同步驱动。
 */
public final class TieringKVOperator implements AutoCloseable {

    public static final String FINALIZER = K8sActionApplier.FINALIZER;
    private static final long RESYNC_INITIAL_DELAY_SECONDS = 2;
    private static final long RESYNC_PERIOD_SECONDS = 5;

    private final KubernetesClient kubernetesClient;
    private final OperatorClient client;
    private final TieringKVReconciler reconciler;
    private final ActionApplier applier;
    private SharedIndexInformer<K8sTieringKVCluster> informer;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final ScheduledExecutorService resyncExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "tiering-kv-operator-resync");
                thread.setDaemon(true);
                return thread;
            });
    private boolean started;

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
        if (started) {
            return;
        }
        started = true;
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
        resyncExecutor.scheduleWithFixedDelay(this::reconcileAll,
                RESYNC_INITIAL_DELAY_SECONDS, RESYNC_PERIOD_SECONDS,
                TimeUnit.SECONDS);
    }

    /** 同步 reconcile：计划 → 执行动作 → 状态回写。 */
    public K8sTieringKVCluster reconcileNow(
            K8sTieringKVCluster resource) {
        ensureFinalizer(resource);
        K8sTieringKVClusterStatus observed = applier.observe(resource);
        if (observed != null) {
            resource.setStatus(observed);
        }
        TieringKVReconciler.ReconcileResult result =
                reconciler.reconcile(resource);
        for (io.tieringkv.operator.OperatorAction action :
                result.actions()) {
            applier.apply(resource, action);
        }
        resource.setStatus(result.status());
        if (isDeleting(resource)) {
            removeFinalizer(resource);
            client.upsert(resource);
        } else {
            client.updateStatus(resource);
        }
        return resource;
    }

    private void reconcileAll() {
        try {
            var list = kubernetesClient.resources(K8sTieringKVCluster.class)
                    .inAnyNamespace().list();
            if (list == null || list.getItems() == null) {
                return;
            }
            for (K8sTieringKVCluster resource : list.getItems()) {
                try {
                    reconcileNow(resource);
                } catch (RuntimeException failure) {
                    System.err.println("[operator] reconcile failed: "
                            + failure.getMessage());
                }
            }
        } catch (RuntimeException failure) {
            System.err.println("[operator] resync failed: "
                    + failure.getMessage());
        }
    }

    private void ensureFinalizer(K8sTieringKVCluster resource) {
        if (resource == null || resource.getMetadata() == null
                || isDeleting(resource)) {
            return;
        }
        var finalizers = resource.getMetadata().getFinalizers();
        if (finalizers == null || !finalizers.contains(FINALIZER)) {
            if (finalizers == null) {
                finalizers = new java.util.ArrayList<>();
            } else {
                finalizers = new java.util.ArrayList<>(finalizers);
            }
            finalizers.add(FINALIZER);
            resource.getMetadata().setFinalizers(finalizers);
            client.upsert(resource);
        }
    }

    private static void removeFinalizer(K8sTieringKVCluster resource) {
        if (resource.getMetadata() == null
                || resource.getMetadata().getFinalizers() == null) {
            return;
        }
        resource.getMetadata().setFinalizers(
                resource.getMetadata().getFinalizers().stream()
                        .filter(finalizer -> !FINALIZER.equals(finalizer))
                        .toList());
    }

    private static boolean isDeleting(K8sTieringKVCluster resource) {
        return resource != null && resource.getMetadata() != null
                && resource.getMetadata().getDeletionTimestamp() != null;
    }

    public void awaitStopped() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (informer != null) {
            informer.close();
        }
        resyncExecutor.shutdownNow();
        stopped.countDown();
    }

    public static void main(String[] args) throws Exception {
        KubernetesClient kubernetesClient =
                new KubernetesClientBuilder().build();
        TieringKVOperator operator = new TieringKVOperator(
                kubernetesClient,
                new Fabric8OperatorClient(kubernetesClient),
                new K8sActionApplier(kubernetesClient));
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
