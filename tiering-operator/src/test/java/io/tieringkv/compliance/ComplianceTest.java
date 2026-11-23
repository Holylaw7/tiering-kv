package io.tieringkv.compliance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据主权与合规（ADR-0143）：驻留策略 + 违规拒绝。 */
class ComplianceTest {

    private static DataResidencyPolicy policy() {
        return new DataResidencyPolicy(Map.of(
                "cn", "china", "us", "us"));
    }

    @Test
    void sameResidencyAllowed() {
        new ComplianceValidator().validate(policy(), "cn", "cn");
        new ComplianceValidator().validate(policy(), "us", "us");
    }

    @Test
    void crossResidencyRejected() {
        assertThatThrownBy(() -> new ComplianceValidator().validate(
                policy(), "cn", "us"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void defaultResidencyForUnknown() {
        DataResidencyPolicy policy = policy();
        assertThat(policy.required("unknown")).isEqualTo("default");
    }

    @ParameterizedTest(name = "region {0}")
    @ValueSource(strings = {"cn", "us", "eu"})
    void parameterizedResidency(String region) {
        DataResidencyPolicy policy = policy();
        assertThat(policy.required(region)).isNotBlank();
    }

    @Test
    void withinSameDefaultAllowed() {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of());
        new ComplianceValidator().validate(policy, "a", "b");
    }

    @Test
    void crossDefaultAndExplicitRejected() {
        assertThatThrownBy(() -> new ComplianceValidator().validate(
                policy(), "unknown", "cn"))
                .isInstanceOf(SecurityException.class);
    }
}
