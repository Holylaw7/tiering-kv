package io.tieringkv.security.network;

import io.tieringkv.security.network.NetworkPolicyDsl.PolicyRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 策略审计视图（ADR-0176）：聚合 + 编译联动。 */
class PolicyAuditViewTest {

    private final PolicyAuditView view = new PolicyAuditView();

    @Test
    void byTenantAggregates() {
        NetworkPolicyAudit audit = audit();
        assertThat(view.byTenant(audit))
                .containsEntry("t1", 1L)
                .containsEntry("t2", 2L)
                .containsEntry("t3", 1L);
    }

    @Test
    void byActionAggregates() {
        NetworkPolicyAudit audit = audit();
        assertThat(view.byAction(audit))
                .containsEntry("allow", 1L)
                .containsEntry("deny", 1L);
    }

    @Test
    void byTenantActionAggregates() {
        NetworkPolicyAudit audit = audit();
        assertThat(view.byTenantAction(audit))
                .containsEntry("t1:allow", 1L)
                .containsEntry("t2:deny", 1L);
    }

    @Test
    void emptyAuditEmptyViews() {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(view.byTenant(audit)).isEmpty();
        assertThat(view.byAction(audit)).isEmpty();
        assertThat(view.byTenantAction(audit)).isEmpty();
    }

    @Test
    void nullAuditRejected() {
        assertThatThrownBy(() -> view.byTenant(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> view.byAction(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compilerAutoAudits() {
        IsolationPolicy policy = policy();
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        new PolicyCompiler().apply(policy,
                "allow: t1 -> t2\ndeny: t1 -> t3", audit);
        assertThat(audit.size()).isEqualTo(2);
        assertThat(view.byAction(audit))
                .containsEntry("allow", 1L)
                .containsEntry("deny", 1L);
    }

    @Test
    void compilerWithoutAuditNoEvents() {
        IsolationPolicy policy = policy();
        new PolicyCompiler().apply(policy, "allow: t1 -> t2");
        assertThat(new NetworkPolicyAudit().size()).isZero();
    }

    @ParameterizedTest(name = "rules {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedAggregates(int count) {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        for (int i = 0; i < count; i++) {
            audit.record("src", new PolicyRule("allow",
                    "t" + i, "t" + (i + 1)), i);
        }
        assertThat(view.byTenant(audit).values())
                .allMatch(value -> value >= 1);
        assertThat(view.byAction(audit))
                .containsEntry("allow", (long) count);
    }

    @Test
    void concurrentViewStable() throws Exception {
        NetworkPolicyAudit audit = audit();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    view.byTenant(audit);
                    view.byAction(audit);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static NetworkPolicyAudit audit() {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        audit.record("allow: t1 -> t2",
                new PolicyRule("allow", "t1", "t2"), 1000);
        audit.record("deny: t2 -> t3",
                new PolicyRule("deny", "t2", "t3"), 2000);
        return audit;
    }

    private static IsolationPolicy policy() {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 1; i <= 3; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        return policy;
    }
}
