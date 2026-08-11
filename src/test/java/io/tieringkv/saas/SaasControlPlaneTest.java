package io.tieringkv.saas;

import io.tieringkv.operator.TieringKVClusterSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SaaS 多租户控制平面（ADR-0118）：注册、审计、集群生成。 */
class SaasControlPlaneTest {

    @Test
    void tenantRegisterAndGet() {
        TenantRegistry registry = new TenantRegistry();
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 5, 100);
        registry.register(tenant);
        assertThat(registry.get("t1").tenant()).isEqualTo(tenant);
        assertThat(registry.get("t1").state())
                .isEqualTo(TenantRegistry.TenantState.ACTIVE);
    }

    @Test
    void duplicateTenantRejected() {
        TenantRegistry registry = new TenantRegistry();
        registry.register(new ClusterTenant("t1", "prod", 3, 10));
        assertThatThrownBy(() -> registry.register(
                new ClusterTenant("t1", "prod2", 3, 10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suspendAndDelete() {
        TenantRegistry registry = new TenantRegistry();
        registry.register(new ClusterTenant("t1", "prod", 3, 10));
        assertThat(registry.suspend("t1")).isTrue();
        assertThat(registry.get("t1").state())
                .isEqualTo(TenantRegistry.TenantState.SUSPENDED);
        assertThat(registry.suspend("missing")).isFalse();
        assertThat(registry.delete("t1")).isTrue();
        assertThat(registry.size()).isZero();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedTenantRegistry(int count) {
        TenantRegistry registry = new TenantRegistry();
        for (int i = 0; i < count; i++) {
            registry.register(new ClusterTenant("t" + i, "c" + i,
                    3, 10));
        }
        assertThat(registry.size()).isEqualTo(count);
        assertThat(registry.tenantIds()).hasSize(count);
    }

    @Test
    void auditLogRecordsActions() {
        TenantAuditLog audit = new TenantAuditLog();
        audit.record("t1", "create");
        audit.record("t1", "backup");
        audit.record("t2", "create");
        assertThat(audit.entries("t1")).hasSize(2);
        assertThat(audit.size()).isEqualTo(3);
        assertThat(audit.entries("t1").get(0).action())
                .isEqualTo("create");
    }

    @Test
    void tenantClusterPlannerGeneratesSpec() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 3, 100);
        TieringKVClusterSpec spec = new TenantClusterPlanner().plan(
                tenant, 3, "tiering-kv:1.1.0");
        assertThat(spec.metadataReplicas()).isEqualTo(3);
        assertThat(spec.storageReplicas()).isEqualTo(3);
        assertThat(spec.regionIds()).hasSize(3);
        assertThat(spec.image()).isEqualTo("tiering-kv:1.1.0");
    }

    @Test
    void tenantClusterPlannerQuotaRejected() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 2, 100);
        assertThatThrownBy(() -> new TenantClusterPlanner().plan(
                tenant, 3, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedClusterPlan(int storage) {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 5, 100);
        TieringKVClusterSpec spec = new TenantClusterPlanner().plan(
                tenant, storage, "v1");
        assertThat(spec.storageReplicas()).isEqualTo(storage);
        assertThat(spec.regionIds()).hasSize(storage);
    }

    @Test
    void auditEmptyTenantEmpty() {
        TenantAuditLog audit = new TenantAuditLog();
        assertThat(audit.entries("t1")).isEmpty();
    }
}
