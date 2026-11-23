package io.tieringkv.security.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 策略编译（ADR-0169）：DSL → IsolationPolicy + 幂等。 */
class PolicyCompilerTest {

    @Test
    void compileAllowRule() {
        IsolationPolicy policy = policy();
        new PolicyCompiler().apply(policy,
                "allow: t1 -> t2");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
        assertThat(policy.canCommunicate("t2", "t1")).isTrue();
    }

    @Test
    void compileDenyRuleRevokes() {
        IsolationPolicy policy = policy();
        policy.allow("t1", "t2");
        new PolicyCompiler().apply(policy,
                "deny: t1 -> t2");
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void compileMultipleRules() {
        IsolationPolicy policy = policy();
        new PolicyCompiler().apply(policy,
                "allow: t1 -> t2\ndeny: t1 -> t3");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
        assertThat(policy.canCommunicate("t1", "t3")).isFalse();
    }

    @Test
    void compileIsIdempotent() {
        IsolationPolicy policy = policy();
        PolicyCompiler compiler = new PolicyCompiler();
        compiler.apply(policy, "allow: t1 -> t2");
        compiler.applyIdempotent(policy, "allow: t1 -> t2");
        assertThat(policy.whitelistEntries()).hasSize(1);
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
    }

    @Test
    void denyAfterAllowOverrides() {
        IsolationPolicy policy = policy();
        new PolicyCompiler().apply(policy,
                "allow: t1 -> t2\ndeny: t1 -> t2");
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void allowAfterDenyOverrides() {
        IsolationPolicy policy = policy();
        new PolicyCompiler().apply(policy,
                "deny: t1 -> t2\nallow: t1 -> t2");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
    }

    @Test
    void unknownTenantCompileRejected() {
        IsolationPolicy policy = policy();
        assertThatThrownBy(() -> new PolicyCompiler().apply(
                policy, "allow: missing -> t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedDslRejected() {
        IsolationPolicy policy = policy();
        assertThatThrownBy(() -> new PolicyCompiler().apply(
                policy, "open: t1 -> t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPolicyRejected() {
        assertThatThrownBy(() -> new PolicyCompiler().apply(
                null, "allow: t1 -> t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDslNoChange() {
        IsolationPolicy policy = policy();
        new PolicyCompiler().apply(policy, "");
        assertThat(policy.whitelistEntries()).isEmpty();
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @ParameterizedTest(name = "rules {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedCompileRules(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i <= count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dsl.append("allow: t").append(i).append(" -> t")
                    .append(i + 1).append('\n');
        }
        new PolicyCompiler().apply(policy, dsl.toString());
        assertThat(policy.whitelistEntries()).hasSize(count);
    }

    @ParameterizedTest(name = "action {0}")
    @ValueSource(strings = {"allow", "deny"})
    void parameterizedCompileActions(String action) {
        IsolationPolicy policy = policy();
        if (action.equals("allow")) {
            new PolicyCompiler().apply(policy,
                    "allow: t1 -> t2");
            assertThat(policy.canCommunicate("t1", "t2")).isTrue();
        } else {
            new PolicyCompiler().apply(policy,
                    "deny: t1 -> t2");
            assertThat(policy.canCommunicate("t1", "t2")).isFalse();
        }
    }

    @Test
    void applyTwiceWithSameRulesStable() {
        IsolationPolicy policy = policy();
        PolicyCompiler compiler = new PolicyCompiler();
        compiler.apply(policy, "allow: t1 -> t2\ndeny: t1 -> t3");
        compiler.apply(policy, "allow: t1 -> t2\ndeny: t1 -> t3");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
        assertThat(policy.canCommunicate("t1", "t3")).isFalse();
        assertThat(policy.whitelistEntries()).hasSize(1);
    }

    @Test
    void compiledPolicyRespectsUnknownTenant() {
        IsolationPolicy policy = policy();
        new PolicyCompiler().apply(policy,
                "allow: t1 -> t2");
        assertThat(policy.canCommunicate("t1", "missing"))
                .isFalse();
    }

    private static IsolationPolicy policy() {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 1; i <= 3; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        return policy;
    }
}
