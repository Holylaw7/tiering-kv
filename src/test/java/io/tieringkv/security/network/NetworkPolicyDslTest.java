package io.tieringkv.security.network;

import io.tieringkv.security.network.NetworkPolicyDsl.PolicyRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 网络策略 DSL（ADR-0169）：解析 + 校验。 */
class NetworkPolicyDslTest {

    @Test
    void parseAllowAndDeny() {
        List<PolicyRule> rules = NetworkPolicyDsl.parse(
                "allow: t1 -> t2\ndeny: t1 -> t3");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0)).isEqualTo(
                new PolicyRule("allow", "t1", "t2"));
        assertThat(rules.get(1)).isEqualTo(
                new PolicyRule("deny", "t1", "t3"));
    }

    @Test
    void parseSkipsCommentsAndBlank() {
        List<PolicyRule> rules = NetworkPolicyDsl.parse(
                "# header\n\nallow: t1 -> t2\n\n# trailing");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).action()).isEqualTo("allow");
    }

    @Test
    void parseEmptyDsl() {
        assertThat(NetworkPolicyDsl.parse("")).isEmpty();
        assertThat(NetworkPolicyDsl.parse("# only comment"))
                .isEmpty();
    }

    @Test
    void nullDslRejected() {
        assertThatThrownBy(() -> NetworkPolicyDsl.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownActionRejected() {
        assertThatThrownBy(() -> NetworkPolicyDsl.parse(
                "open: t1 -> t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingArrowRejected() {
        assertThatThrownBy(() -> NetworkPolicyDsl.parse(
                "allow: t1 t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingColonRejected() {
        assertThatThrownBy(() -> NetworkPolicyDsl.parse(
                "allow t1 -> t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankFromRejected() {
        assertThatThrownBy(() -> NetworkPolicyDsl.parse(
                "allow: -> t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankToRejected() {
        assertThatThrownBy(() -> NetworkPolicyDsl.parse(
                "allow: t1 ->"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whitespaceTolerant() {
        List<PolicyRule> rules = NetworkPolicyDsl.parse(
                "  allow  :   t1   ->   t2  ");
        assertThat(rules.get(0).from()).isEqualTo("t1");
        assertThat(rules.get(0).to()).isEqualTo("t2");
    }

    @ParameterizedTest(name = "rules {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedRuleCounts(int count) {
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dsl.append("allow: t").append(i).append(" -> t")
                    .append(i + 1).append('\n');
        }
        assertThat(NetworkPolicyDsl.parse(dsl.toString()))
                .hasSize(count);
    }

    @ParameterizedTest(name = "action {0}")
    @ValueSource(strings = {"allow", "deny"})
    void parameterizedActions(String action) {
        List<PolicyRule> rules = NetworkPolicyDsl.parse(
                action + ": t1 -> t2");
        assertThat(rules.get(0).action()).isEqualTo(action);
    }

    @Test
    void multipleRulesPreserveOrder() {
        List<PolicyRule> rules = NetworkPolicyDsl.parse(
                "allow: a -> b\ndeny: c -> d\nallow: e -> f");
        assertThat(rules).extracting(PolicyRule::action)
                .containsExactly("allow", "deny", "allow");
    }

    @Test
    void parseTrimsArrowSpacing() {
        List<PolicyRule> rules = NetworkPolicyDsl.parse(
                "allow:t1->t2");
        assertThat(rules.get(0).from()).isEqualTo("t1");
        assertThat(rules.get(0).to()).isEqualTo("t2");
    }
}
