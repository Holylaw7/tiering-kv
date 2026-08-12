package io.tieringkv.security.network;

import io.tieringkv.security.network.PolicyRiskScorer.RiskScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 策略风险评分（ADR-0184）：规则驱动评分。 */
class PolicyRiskScorerTest {

    private final PolicyRiskScorer scorer = new PolicyRiskScorer();

    @Test
    void isolatedPolicyZeroRisk() {
        RiskScore risk = scorer.score(policy(5, false));
        assertThat(risk.score()).isZero();
        assertThat(risk.allowPairs()).isZero();
        assertThat(risk.privateExposure()).isFalse();
    }

    @Test
    void allowPairsIncreaseScore() {
        IsolationPolicy policy = policy(4, false);
        policy.allow("t0", "t1");
        policy.allow("t2", "t3");
        RiskScore risk = scorer.score(policy);
        assertThat(risk.allowPairs()).isEqualTo(2);
        assertThat(risk.score()).isEqualTo(20);
    }

    @Test
    void privateExposureAddsPenalty() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        RiskScore risk = scorer.score(policy);
        assertThat(risk.privateExposure()).isTrue();
        assertThat(risk.score()).isEqualTo(30);
    }

    @Test
    void scoreCappedAtHundred() {
        IsolationPolicy policy = policy(20, false);
        for (int i = 0; i < 15; i++) {
            policy.allow("t" + i, "t" + ((i + 1) % 20));
        }
        RiskScore risk = scorer.score(policy);
        assertThat(risk.score()).isEqualTo(100);
    }

    @Test
    void publicTenantPairNoPrivatePenalty() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc", "subnet", false));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc", "subnet", false));
        policy.allow("t1", "t2");
        RiskScore risk = scorer.score(policy);
        assertThat(risk.privateExposure()).isFalse();
        assertThat(risk.score()).isEqualTo(10);
    }

    @Test
    void nullPolicyRejected() {
        assertThatThrownBy(() -> scorer.score(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedPairCounts(int pairs) {
        IsolationPolicy policy = policy(pairs + 1, false);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        RiskScore risk = scorer.score(policy);
        assertThat(risk.score()).isEqualTo(pairs * 10);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {2, 10, 50})
    void parameterizedTenantCounts(int count) {
        RiskScore risk = scorer.score(policy(count, false));
        assertThat(risk.score()).isZero();
    }

    @Test
    void scoreExplainable() {
        IsolationPolicy policy = policy(4, true);
        policy.allow("t0", "t1");
        RiskScore risk = scorer.score(policy);
        assertThat(risk.score()).isEqualTo(10 + 20);
        assertThat(risk.allowPairs()).isEqualTo(1);
        assertThat(risk.privateExposure()).isTrue();
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
