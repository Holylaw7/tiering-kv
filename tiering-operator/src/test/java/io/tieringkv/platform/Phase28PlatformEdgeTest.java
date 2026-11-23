package io.tieringkv.platform;

import io.tieringkv.dr.DrRole;
import io.tieringkv.dr.DrSwitchPlanner;
import io.tieringkv.dr.DrTopology;
import io.tieringkv.monitor.Phase28Metrics;
import io.tieringkv.replication.ReplicationMode;
import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.TenantClusterPlanner;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.hnsw.HnswIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 平台边缘：DR/SaaS/HNSW/指标参数矩阵。 */
class Phase28PlatformEdgeTest {

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 4})
    void drTopologyAllRoles(int regions) {
        Map<String, DrRole> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < regions; i++) {
            roles.put("r" + i, i == 0 ? DrRole.PRIMARY
                    : i == 1 ? DrRole.SECONDARY : DrRole.OBSERVER);
        }
        DrTopology topology = new DrTopology(roles, Map.of());
        assertThat(new DrSwitchPlanner().failover(topology, "r0").safe())
                .isTrue();
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(ints = {1, 5})
    void tenantPlanStorageReplicas(int storage) {
        ClusterTenant tenant = new ClusterTenant("t", "c", 5, 100);
        io.tieringkv.operator.TieringKVClusterSpec spec =
                new TenantClusterPlanner().plan(tenant, storage, "v1");
        assertThat(spec.regionIds()).hasSize(storage);
        assertThat(spec.storageReplicas()).isEqualTo(storage);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10})
    void tenantRegistryVolume(int count) {
        TenantRegistry registry = new TenantRegistry();
        for (int i = 0; i < count; i++) {
            registry.register(new ClusterTenant("t" + i, "c" + i,
                    3, 10));
        }
        assertThat(registry.tenantIds()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, 20})
    void tenantAuditVolume(int count) {
        TenantAuditLog audit = new TenantAuditLog();
        for (int i = 0; i < count; i++) {
            audit.record("t1", "op" + i);
        }
        assertThat(audit.entries("t1")).hasSize(count);
        assertThat(audit.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "vectors {0}")
    @ValueSource(ints = {10, 200})
    void hnswBuildVolume(int count) {
        HnswIndex index = new HnswIndex(3);
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            embeddings.add(new Embedding("e" + i,
                    new float[]{i % 3, 3 - i % 3}));
        }
        index.build(embeddings);
        assertThat(index.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "topK {0}")
    @ValueSource(ints = {1, 10})
    void vectorSearchTopK(int topK) {
        VectorStore store = new VectorStore();
        for (int i = 0; i < 50; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 4 + 1, 4 - i % 4}));
        }
        assertThat(store.search(new float[]{1, 1}, topK))
                .hasSize(Math.min(topK, 50));
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {0, 99})
    void metricsGaugeBoundaries(long value) {
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.gauge("g", value);
        assertThat(metrics.gauge("g")).isEqualTo(value);
    }

    @Test
    void metricsAllNames() {
        Phase28Metrics metrics = new Phase28Metrics();
        for (String name : new String[]{"replication_lag",
                "crdt_conflicts", "dr_rpo", "sql_query_p99",
                "vector_recall", "tenant_active"}) {
            metrics.increment(name);
        }
        assertThat(metrics.snapshot()).hasSize(6);
    }
}
