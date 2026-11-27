package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiering-KV Operator（ADR-0322 M4 / v5.4.0 A2 生产运行态加固）：
 * Watch TieringKVCluster → 每 CR 串行互斥 reconcile → 动作执行 → 状态回写。
 *
 * <p>生产特性：每 CR 细粒度互斥锁、可选命名空间隔离（{@code WATCH_NAMESPACE}）、
 * 可选 K8s Lease 选主（{@code OPERATOR_LEADER_ELECTION=true}）、零依赖健康探针
 * （{@code OPERATOR_HEALTH_PORT=8088}）与 {@code SIGTERM} 优雅停机。</p>
 */
public final class TieringKVOperator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TieringKVOperator.class);

    public static final String FINALIZER = K8sActionApplier.FINALIZER;
    private static final long RESYNC_INITIAL_DELAY_SECONDS = 2;
    private static final long RESYNC_PERIOD_SECONDS = 5;
    private static final int DEFAULT_HEALTH_PORT = 8088;
    private static final int DEFAULT_LEASE_DURATION_SECONDS = 15;

    private final KubernetesClient kubernetesClient;
    private final OperatorClient client;
    private final TieringKVReconciler reconciler;
    private final ActionApplier applier;
    private final String watchNamespace;
    private final OperatorLeaderElector leaderElector;
    private final ConcurrentHashMap<String, ReentrantLock> resourceLocks =
            new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean watching = new AtomicBoolean(false);
    private final ScheduledExecutorService resyncExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "tiering-kv-operator-resync");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledExecutorService electionExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "tiering-kv-operator-election");
                thread.setDaemon(true);
                return thread;
            });
    private volatile SharedIndexInformer<K8sTieringKVCluster> informer;
    private boolean started;

    public TieringKVOperator(KubernetesClient kubernetesClient,
                             OperatorClient client,
                             ActionApplier applier) {
        this(kubernetesClient, client, applier, null, null);
    }

    public TieringKVOperator(KubernetesClient kubernetesClient,
                             OperatorClient client,
                             ActionApplier applier,
                             String watchNamespace,
                             OperatorLeaderElector leaderElector) {
        if (kubernetesClient == null || client == null
                || applier == null) {
            throw new IllegalArgumentException(
                    "kubernetesClient, client and applier required");
        }
        this.kubernetesClient = kubernetesClient;
        this.client = client;
        this.applier = applier;
        this.watchNamespace = (watchNamespace == null || watchNamespace.isBlank())
                ? null : watchNamespace.trim();
        this.leaderElector = leaderElector;
        this.reconciler = new TieringKVReconciler();
    }

    public String watchNamespace() {
        return watchNamespace;
    }

    /** 探针就绪判定：已启动、未关闭且正在监听（若开启选主则须持有 Lease）。 */
    public boolean isReady() {
        return !closed.get() && watching.get()
                && (leaderElector == null || leaderElector.isLeader());
    }

    /** 启动 Watch + reconcile 循环（非阻塞）；启用选主时先抢占 Lease。 */
    public synchronized void start() {
        if (started || closed.get()) {
            return;
        }
        started = true;
        if (leaderElector == null) {
            startWatching();
            return;
        }
        long renewSeconds = Math.max(2L, leaderElector.leaseDurationSeconds() / 3L);
        pollLeadership();
        electionExecutor.scheduleWithFixedDelay(
                this::pollLeadership, renewSeconds, renewSeconds, TimeUnit.SECONDS);
    }

    private void pollLeadership() {
        if (closed.get()) {
            return;
        }
        boolean acquired = leaderElector.tryAcquireOrRenew();
        if (acquired && !watching.get()) {
            log.info("[operator] acquired leadership lease={} identity={}",
                    leaderElector.leaseName(), leaderElector.identity());
            startWatching();
        } else if (!acquired && watching.get()) {
            log.warn("[operator] lost leadership lease={}, pausing reconcile",
                    leaderElector.leaseName());
            stopWatching();
        }
    }

    private synchronized void startWatching() {
        if (watching.get() || closed.get()) {
            return;
        }
        watching.set(true);
        ResourceEventHandler<K8sTieringKVCluster> handler =
                new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(K8sTieringKVCluster resource) {
                        reconcileSafely(resource);
                    }

                    @Override
                    public void onUpdate(K8sTieringKVCluster old,
                                         K8sTieringKVCluster updated) {
                        reconcileSafely(updated);
                    }

                    @Override
                    public void onDelete(K8sTieringKVCluster resource,
                                         boolean deletedFinalStateUnknown) {
                        // 资源删除：清理由 Finalizer + K8s GC 负责
                    }
                };
        informer = watchNamespace == null
                ? kubernetesClient.resources(K8sTieringKVCluster.class)
                        .inAnyNamespace().inform(handler)
                : kubernetesClient.resources(K8sTieringKVCluster.class)
                        .inNamespace(watchNamespace).inform(handler);
        resyncExecutor.scheduleWithFixedDelay(this::reconcileAll,
                RESYNC_INITIAL_DELAY_SECONDS, RESYNC_PERIOD_SECONDS,
                TimeUnit.SECONDS);
    }

    private synchronized void stopWatching() {
        watching.set(false);
        SharedIndexInformer<K8sTieringKVCluster> current = informer;
        informer = null;
        if (current != null) {
            current.close();
        }
    }

    private void reconcileSafely(K8sTieringKVCluster resource) {
        if (!watching.get() || closed.get()) {
            return;
        }
        try {
            reconcileNow(resource);
        } catch (RuntimeException failure) {
            log.error("[operator] event reconcile failed: {}", failure.getMessage(), failure);
        }
    }

    /** 同步 reconcile（每 CR 串行互斥）：计划 → 执行动作 → 状态回写。 */
    public K8sTieringKVCluster reconcileNow(
            K8sTieringKVCluster resource) {
        String key = lockKey(resource);
        ReentrantLock lock = resourceLocks.computeIfAbsent(
                key, ignored -> new ReentrantLock());
        lock.lock();
        try {
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
                resourceLocks.remove(key, lock);
            } else {
                client.updateStatus(resource);
            }
            return resource;
        } finally {
            lock.unlock();
        }
    }

    private void reconcileAll() {
        if (!watching.get() || closed.get()) {
            return;
        }
        try {
            var list = watchNamespace == null
                    ? kubernetesClient.resources(K8sTieringKVCluster.class)
                            .inAnyNamespace().list()
                    : kubernetesClient.resources(K8sTieringKVCluster.class)
                            .inNamespace(watchNamespace).list();
            if (list == null || list.getItems() == null) {
                return;
            }
            for (K8sTieringKVCluster resource : list.getItems()) {
                reconcileSafely(resource);
            }
        } catch (RuntimeException failure) {
            log.error("[operator] resync failed: {}", failure.getMessage(), failure);
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

    private static String lockKey(K8sTieringKVCluster resource) {
        if (resource == null || resource.getMetadata() == null) {
            return "default/default";
        }
        String ns = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        return (ns == null || ns.isBlank() ? "default" : ns)
                + "/" + (name == null || name.isBlank() ? "default" : name);
    }

    public void awaitStopped() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopWatching();
        electionExecutor.shutdownNow();
        resyncExecutor.shutdownNow();
        if (leaderElector != null) {
            leaderElector.release();
        }
        stopped.countDown();
    }

    private static int parseIntEnv(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String resolveIdentity() {
        String explicit = System.getenv("OPERATOR_IDENTITY");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String podName = System.getenv("POD_NAME");
        if (podName != null && !podName.isBlank()) {
            return podName.trim();
        }
        String host = "operator";
        try {
            String resolved = InetAddress.getLocalHost().getHostName();
            if (resolved != null && !resolved.isBlank()) {
                host = resolved.trim();
            }
        } catch (Exception ignored) {
            // 无反向 DNS 时使用默认前缀
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static void main(String[] args) throws Exception {
        String watchNs = System.getenv("WATCH_NAMESPACE");
        int healthPort = parseIntEnv("OPERATOR_HEALTH_PORT", DEFAULT_HEALTH_PORT);
        boolean leaderElection = "true".equalsIgnoreCase(
                System.getenv("OPERATOR_LEADER_ELECTION"));
        String leaseNs = System.getenv("OPERATOR_LEASE_NAMESPACE");
        if (leaseNs == null || leaseNs.isBlank()) {
            leaseNs = System.getenv("POD_NAMESPACE");
        }
        if (leaseNs == null || leaseNs.isBlank()) {
            leaseNs = "tiering-kv";
        }
        String leaseName = System.getenv("OPERATOR_LEASE_NAME");
        if (leaseName == null || leaseName.isBlank()) {
            leaseName = "tiering-kv-operator-leader";
        }
        int leaseDuration = parseIntEnv(
                "OPERATOR_LEASE_DURATION_SECONDS", DEFAULT_LEASE_DURATION_SECONDS);

        KubernetesClient kubernetesClient =
                new KubernetesClientBuilder().build();
        OperatorLeaderElector elector = leaderElection
                ? new OperatorLeaderElector(kubernetesClient, leaseNs, leaseName,
                        resolveIdentity(), leaseDuration)
                : null;
        TieringKVOperator operator = new TieringKVOperator(
                kubernetesClient,
                new Fabric8OperatorClient(kubernetesClient),
                new K8sActionApplier(kubernetesClient),
                watchNs,
                elector);
        OperatorHealthServer healthServer = healthPort > 0
                ? new OperatorHealthServer(healthPort, operator::isReady)
                : null;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            operator.close();
            if (healthServer != null) {
                healthServer.close();
            }
        }, "tiering-kv-operator-shutdown"));
        try (operator) {
            if (healthServer != null) {
                healthServer.start();
                log.info("Tiering-KV Operator health server on :{}", healthServer.boundPort());
            }
            operator.start();
            log.info("Tiering-KV Operator watching tieringkvclusters (scope={}) [leader-election={}]",
                    watchNs == null || watchNs.isBlank() ? "all-namespaces" : watchNs,
                    leaderElection ? "enabled" : "disabled");
            operator.awaitStopped();
        } finally {
            if (healthServer != null) {
                healthServer.close();
            }
            kubernetesClient.close();
        }
    }
}
