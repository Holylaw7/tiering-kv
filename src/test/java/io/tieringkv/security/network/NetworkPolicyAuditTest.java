package io.tieringkv.security.network;

import io.tieringkv.security.network.NetworkPolicyAudit.AuditEvent;
import io.tieringkv.security.network.NetworkPolicyDsl.PolicyRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 网络策略审计（ADR-0176）：事件记录 + 过滤。 */
class NetworkPolicyAuditTest {

    @Test
    void recordAddsEvent() {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        audit.record("allow: t1 -> t2",
                new PolicyRule("allow", "t1", "t2"), 1000);
        assertThat(audit.size()).isEqualTo(1);
        AuditEvent event = audit.events().get(0);
        assertThat(event.action()).isEqualTo("allow");
        assertThat(event.source()).contains("t1 -> t2");
        assertThat(event.timestampMillis()).isEqualTo(1000);
    }

    @Test
    void forTenantMatchesEitherSide() {
        NetworkPolicyAudit audit = audit();
        assertThat(audit.forTenant("t2")).hasSize(2);
        assertThat(audit.forTenant("t1")).hasSize(1);
        assertThat(audit.forTenant("t3")).hasSize(1);
        assertThat(audit.forTenant("missing")).isEmpty();
    }

    @Test
    void sinceFiltersByTime() {
        NetworkPolicyAudit audit = audit();
        assertThat(audit.since(1500)).hasSize(1);
        assertThat(audit.since(3000)).isEmpty();
    }

    @Test
    void eventsAreCopied() {
        NetworkPolicyAudit audit = audit();
        java.util.List<AuditEvent> view = audit.events();
        audit.record("allow: t3 -> t4",
                new PolicyRule("allow", "t3", "t4"), 4000);
        assertThat(view).hasSize(2);
        assertThat(audit.size()).isEqualTo(3);
    }

    @Test
    void nullRuleRejected() {
        assertThatThrownBy(() -> new NetworkPolicyAudit().record(
                "src", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTenantRejected() {
        assertThatThrownBy(() -> new AuditEvent("", "t2",
                "allow", "src", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankActionRejected() {
        assertThatThrownBy(() -> new AuditEvent("t1", "t2",
                "", "src", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSourceRejected() {
        assertThatThrownBy(() -> new AuditEvent("t1", "t2",
                "allow", " ", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedEventCounts(int count) {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        for (int i = 0; i < count; i++) {
            audit.record("src", new PolicyRule("allow",
                    "t" + i, "t" + (i + 1)), i);
        }
        assertThat(audit.size()).isEqualTo(count);
    }

    @Test
    void concurrentRecordsSafe() throws Exception {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    audit.record("src", new PolicyRule("allow",
                            "t" + i, "t" + (i + 1)), i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(audit.size()).isEqualTo(200);
    }

    private static NetworkPolicyAudit audit() {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        audit.record("allow: t1 -> t2",
                new PolicyRule("allow", "t1", "t2"), 1000);
        audit.record("deny: t2 -> t3",
                new PolicyRule("deny", "t2", "t3"), 2000);
        return audit;
    }
}
