package io.tieringkv.security.network;

import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 网络隔离集成（ADR-0161）：租户注册 + 策略 + RBAC。 */
class NetworkIsolationIntegrationTest {

    @Test
    void registeredTenantsGetIsolationDomains() {
        TenantRegistry tenants = new TenantRegistry();
        tenants.register(new ClusterTenant("t1", "prod", 3, 100));
        tenants.register(new ClusterTenant("t2", "dev", 2, 50));
        IsolationPolicy policy = new IsolationPolicy();
        for (String tenantId : tenants.tenantIds()) {
            policy.register(new NetworkIsolationDomain(
                    tenantId, "vpc-" + tenantId,
                    "subnet-" + tenantId, true));
        }
        assertThat(policy.domainCount()).isEqualTo(2);
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void adminPolicyManagementWithRbac() {
        CredentialManager credentials = new CredentialManager();
        String admin = credentials.issue(Role.ADMIN, 60_000);
        credentials.require(admin,
                io.tieringkv.security.Permission.ADMIN);
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc-2", "subnet-2", true));
        policy.allow("t1", "t2");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {3, 10})
    void parameterizedTenantIsolation(int count) {
        TenantRegistry tenants = new TenantRegistry();
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            tenants.register(new ClusterTenant("t" + i,
                    "c" + i, 2, 50));
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        assertThat(policy.domainCount()).isEqualTo(count);
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                assertThat(policy.canCommunicate("t" + i, "t" + j))
                        .isEqualTo(i == j);
            }
        }
    }

    @Test
    void whitelistedPeeringOnlyForSpecificTenants() {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < 5; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        policy.allow("t0", "t1");
        assertThat(policy.canCommunicate("t0", "t1")).isTrue();
        assertThat(policy.canCommunicate("t0", "t2")).isFalse();
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }
}
