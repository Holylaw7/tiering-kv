package io.tieringkv.security.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自适应加固（ADR-0190）：阈值 → 撤销 + 回滚 + 审计。 */
class AdaptiveHardenerTest {

    private final AdaptiveHardener hardener =
            new AdaptiveHardener();

    @Test
    void highRiskRevokesAllPairs() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        policy.allow("t2", "t3");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        int revoked = hardener.harden(policy, 30, audit);
        assertThat(revoked).isEqualTo(2);
        assertThat(policy.whitelistEntries()).isEmpty();
        assertThat(policy.canCommunicate("t0", "t1")).isFalse();
        assertThat(audit.size()).isEqualTo(2);
    }

    @Test
    void lowRiskNoAction() {
        IsolationPolicy policy = policy(4, false);
        policy.allow("t0", "t1");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(hardener.harden(policy, 50, audit)).isZero();
        assertThat(policy.canCommunicate("t0", "t1")).isTrue();
        assertThat(audit.size()).isZero();
    }

    @Test
    void thresholdBoundaryTriggers() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(hardener.harden(policy, 30, audit))
                .isEqualTo(1);
    }

    @Test
    void rollbackRestoresPairs() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        policy.allow("t2", "t3");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        hardener.harden(policy, 30, audit);
        int restored = hardener.rollback(policy, audit);
        assertThat(restored).isEqualTo(2);
        assertThat(policy.canCommunicate("t0", "t1")).isTrue();
        assertThat(policy.canCommunicate("t2", "t3")).isTrue();
        assertThat(hardener.revokedCount()).isZero();
        assertThat(audit.size()).isEqualTo(4);
    }

    @Test
    void rollbackWithoutHardeningNoop() {
        IsolationPolicy policy = policy(4, false);
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(hardener.rollback(policy, audit)).isZero();
    }

    @Test
    void nullPolicyRejected() {
        assertThatThrownBy(() -> hardener.harden(null, 30,
                new NetworkPolicyAudit()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAuditRejected() {
        assertThatThrownBy(() -> hardener.harden(
                new IsolationPolicy(), 30, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokedCountTracked() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        hardener.harden(policy, 30, audit);
        assertThat(hardener.revokedCount()).isEqualTo(1);
        hardener.rollback(policy, audit);
        assertThat(hardener.revokedCount()).isZero();
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedPairCounts(int pairs) {
        IsolationPolicy policy = policy(pairs + 2, true);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(hardener.harden(policy, 30, audit))
                .isEqualTo(pairs);
        assertThat(policy.whitelistEntries()).isEmpty();
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {0, 10, 30, 50, 100})
    void parameterizedThresholds(int threshold) {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        int revoked = hardener.harden(policy, threshold, audit);
        assertThat(revoked).isEqualTo(threshold <= 30 ? 1 : 0);
    }

    @Test
    void auditRecordsSourceAndAction() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        hardener.harden(policy, 30, audit);
        var event = audit.events().get(0);
        assertThat(event.action()).isEqualTo("deny");
        assertThat(event.source()).contains("hardening");
    }

    @Test
    void concurrentHardenRollbackStable() throws Exception {
        IsolationPolicy policy = policy(10, true);
        for (int i = 0; i < 5; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        Thread hardenerThread = new Thread(() ->
                hardener.harden(policy, 30, audit));
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                policy.canCommunicate("t0", "t1");
            }
        });
        hardenerThread.start();
        reader.start();
        hardenerThread.join(10_000);
        reader.join(10_000);
        assertThat(policy.whitelistEntries()).isEmpty();
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
