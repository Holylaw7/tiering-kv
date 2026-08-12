package io.tieringkv.security.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 风险视图（ADR-0184）：按租户聚合。 */
class RiskDashboardTest {

    private final RiskDashboard dashboard = new RiskDashboard();

    @Test
    void exposureByTenant() {
        IsolationPolicy policy = policy(4, false);
        policy.allow("t0", "t1");
        policy.allow("t1", "t2");
        assertThat(dashboard.exposureByTenant(policy))
                .containsEntry("t1", 2L)
                .containsEntry("t0", 1L)
                .containsEntry("t2", 1L)
                .doesNotContainKey("t3");
    }

    @Test
    void scoreByTenant() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        assertThat(dashboard.scoreByTenant(policy))
                .containsEntry("t0", 30)
                .containsEntry("t1", 30)
                .containsEntry("t2", 0)
                .containsEntry("t3", 0);
    }

    @Test
    void publicTenantsLowerScore() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc", "subnet", false));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc", "subnet", false));
        policy.allow("t1", "t2");
        assertThat(dashboard.scoreByTenant(policy))
                .containsEntry("t1", 10)
                .containsEntry("t2", 10);
    }

    @Test
    void emptyPolicyEmptyViews() {
        assertThat(dashboard.exposureByTenant(
                new IsolationPolicy())).isEmpty();
        assertThat(dashboard.scoreByTenant(
                new IsolationPolicy())).isEmpty();
    }

    @Test
    void nullPolicyRejected() {
        assertThatThrownBy(() -> dashboard.exposureByTenant(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dashboard.scoreByTenant(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedExposure(int pairs) {
        IsolationPolicy policy = policy(pairs + 2, false);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        long total = dashboard.exposureByTenant(policy).values()
                .stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(2L * pairs);
    }

    @Test
    void concurrentViewStable() throws Exception {
        IsolationPolicy policy = policy(10, true);
        policy.allow("t0", "t1");
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    dashboard.exposureByTenant(policy);
                    dashboard.scoreByTenant(policy);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @Test
    void tenantIdsExposed() {
        IsolationPolicy policy = policy(3, false);
        assertThat(policy.tenantIds()).containsExactlyInAnyOrder(
                "t0", "t1", "t2");
    }

    private static IsolationPolicy policy(int count,
                                          boolean privateAll) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i,
                    privateAll));
        }
        return policy;
    }
}
