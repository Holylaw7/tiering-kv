package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.tieringkv.operator.OperatorAction;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** v5.4.0 A2：Operator 健康探针、K8s Lease 选主、每 CR 互斥锁与部署清单校验。 */
@EnableKubernetesMockClient(crud = true)
class OperatorRuntimeTest {

    KubernetesClient kubernetesClient;

    @Test
    void healthServerRespondsToLivenessAndDynamicReadiness() throws Exception {
        AtomicBoolean ready = new AtomicBoolean(false);
        try (OperatorHealthServer server =
                     new OperatorHealthServer(0, ready::get)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            int port = server.boundPort();

            HttpResponse<String> live = http.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(live.statusCode()).isEqualTo(200);
            assertThat(live.body()).contains("\"UP\"");

            HttpResponse<String> notReady = http.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/readyz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(notReady.statusCode()).isEqualTo(503);

            ready.set(true);
            HttpResponse<String> readyRes = http.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/readyz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(readyRes.statusCode()).isEqualTo(200);
            assertThat(readyRes.body()).contains("\"READY\"");
        }
    }

    @Test
    void leaseLeaderElectionEnforcesMutualExclusionExpiryAndGracefulRelease() {
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-11-25T14:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        OperatorLeaderElector opA = new OperatorLeaderElector(
                kubernetesClient, "tiering-kv", "tiering-kv-operator-leader",
                "operator-a", 15, clock);
        OperatorLeaderElector opB = new OperatorLeaderElector(
                kubernetesClient, "tiering-kv", "tiering-kv-operator-leader",
                "operator-b", 15, clock);

        assertThat(opA.tryAcquireOrRenew()).isTrue();
        assertThat(opA.isLeader()).isTrue();
        assertThat(opB.tryAcquireOrRenew()).isFalse();
        assertThat(opB.isLeader()).isFalse();

        // 租约内原地续租仍保持主控权
        now.set(now.get().plusSeconds(5));
        assertThat(opA.tryAcquireOrRenew()).isTrue();
        assertThat(opB.tryAcquireOrRenew()).isFalse();

        // A 失联超过 15s 后 B 自动接管且 leaseTransitions 递增
        now.set(now.get().plusSeconds(16));
        assertThat(opB.tryAcquireOrRenew()).isTrue();
        assertThat(opB.isLeader()).isTrue();
        var lease = kubernetesClient.leases().inNamespace("tiering-kv")
                .withName("tiering-kv-operator-leader").get();
        assertThat(lease.getSpec().getHolderIdentity()).isEqualTo("operator-b");
        assertThat(lease.getSpec().getLeaseTransitions()).isEqualTo(1);

        // B 优雅释放后 A 立即接管
        opB.release();
        assertThat(opB.isLeader()).isFalse();
        assertThat(opA.tryAcquireOrRenew()).isTrue();
        assertThat(opA.isLeader()).isTrue();
    }

    @Test
    void operatorSerializesConcurrentReconcilePerClusterAndTracksReadiness()
            throws Exception {
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);

        OperatorClient fakeClient = new OperatorClient() {
            @Override
            public void upsert(K8sTieringKVCluster resource) {
            }

            @Override
            public void updateStatus(K8sTieringKVCluster resource) {
            }
        };

        ActionApplier slowApplier = (resource, action) -> {
            int concurrency = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrency, Math::max);
            entered.countDown();
            try {
                allowExit.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        };

        try (TieringKVOperator operator = new TieringKVOperator(
                kubernetesClient, fakeClient, slowApplier, "tiering-kv", null)) {
            assertThat(operator.watchNamespace()).isEqualTo("tiering-kv");
            assertThat(operator.isReady()).isFalse();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                K8sTieringKVCluster c1 = sampleCluster("same-cr");
                K8sTieringKVCluster c2 = sampleCluster("same-cr");
                var f1 = pool.submit(() -> operator.reconcileNow(c1));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var f2 = pool.submit(() -> operator.reconcileNow(c2));
                Thread.sleep(100);
                allowExit.countDown();
                f1.get(5, TimeUnit.SECONDS);
                f2.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertThat(maxConcurrent.get()).isEqualTo(1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorManifestsEnforceLeastPrivilegeAndNonRootProbes() throws Exception {
        // 1. RBAC 清单必须遵循最小权限：严禁给 persistentvolumeclaims 授予 delete 权限
        List<Map<String, Object>> rbacDocs = new ArrayList<>();
        try (var reader = Files.newBufferedReader(
                Path.of("deploy", "operator", "rbac.yaml"))) {
            for (Object doc : new Yaml().loadAll(reader)) {
                if (doc instanceof Map<?, ?> map) {
                    rbacDocs.add((Map<String, Object>) map);
                }
            }
        }
        assertThat(rbacDocs).extracting(d -> d.get("kind"))
                .containsExactly("Namespace", "ServiceAccount", "ClusterRole",
                        "ClusterRoleBinding");
        Map<String, Object> clusterRole = rbacDocs.get(2);
        List<Map<String, Object>> rules =
                (List<Map<String, Object>>) clusterRole.get("rules");
        Map<String, Object> pvcRule = rules.stream()
                .filter(r -> ((List<String>) r.get("resources"))
                        .contains("persistentvolumeclaims"))
                .findFirst().orElseThrow();
        assertThat((List<String>) pvcRule.get("verbs"))
                .doesNotContain("delete", "deletecollection", "*");

        // 2. Deployment 清单必须非 root 且配置 /healthz 与 /readyz 探针
        Map<String, Object> dep;
        try (var reader = Files.newBufferedReader(
                Path.of("deploy", "operator", "deployment.yaml"))) {
            dep = (Map<String, Object>) new Yaml().load(reader);
        }
        assertThat(dep.get("kind")).isEqualTo("Deployment");
        Map<String, Object> podSpec = (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) dep.get("spec"))
                        .get("template")).get("spec");
        assertThat(((Map<String, Object>) podSpec.get("securityContext"))
                .get("runAsNonRoot")).isEqualTo(true);
        Map<String, Object> container =
                ((List<Map<String, Object>>) podSpec.get("containers")).get(0);
        assertThat(((Map<String, Object>) ((Map<String, Object>)
                container.get("livenessProbe")).get("httpGet")).get("path"))
                .isEqualTo("/healthz");
        assertThat(((Map<String, Object>) ((Map<String, Object>)
                container.get("readinessProbe")).get("httpGet")).get("path"))
                .isEqualTo("/readyz");

        // 3. kind-cluster-sample.yaml 合法且为轻量演练规格
        Map<String, Object> kindSample;
        try (var reader = Files.newBufferedReader(
                Path.of("deploy", "operator", "kind-cluster-sample.yaml"))) {
            kindSample = (Map<String, Object>) new Yaml().load(reader);
        }
        assertThat(kindSample.get("kind")).isEqualTo("TieringKVCluster");
        Map<String, Object> kindSpec =
                (Map<String, Object>) kindSample.get("spec");
        assertThat(kindSpec.get("metadataReplicas")).isEqualTo(1);
        assertThat(kindSpec.get("storageReplicas")).isEqualTo(2);
    }

    private static K8sTieringKVCluster sampleCluster(String name) {
        K8sTieringKVCluster cluster = new K8sTieringKVCluster();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace("tiering-kv");
        meta.setUid("uid-" + name);
        meta.setGeneration(1L);
        cluster.setMetadata(meta);
        K8sTieringKVClusterSpec spec = new K8sTieringKVClusterSpec();
        spec.setMetadataReplicas(3);
        spec.setStorageReplicas(2);
        spec.setRegionIds(List.of("r1", "r2"));
        spec.setImage("tiering-kv:v1");
        cluster.setSpec(spec);
        return cluster;
    }
}
