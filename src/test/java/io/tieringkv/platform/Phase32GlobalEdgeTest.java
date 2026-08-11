package io.tieringkv.platform;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.replication.active.LeaderSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 32 全球边缘：选主/合规参数矩阵。 */
class Phase32GlobalEdgeTest {

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            20, 30, 50, 100, 200})
    void leaderSelectorRegions(int count) {
        Map<String, java.util.function.BooleanSupplier> health =
                new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            health.put("r" + i, () -> true);
        }
        LeaderSelector selector = new LeaderSelector(health, "r0");
        assertThat(selector.selectLeader()).isEqualTo("r0");
        assertThat(selector.majorityHealthy()).isTrue();
    }

    @ParameterizedTest(name = "down {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 20, 30, 50, 100})
    void leaderSelectorFailures(int down) {
        Map<String, java.util.function.BooleanSupplier> health =
                new LinkedHashMap<>();
        int regions = 101;
        for (int i = 0; i < regions; i++) {
            final int index = i;
            health.put("r" + i, () -> index != down);
        }
        LeaderSelector selector = new LeaderSelector(health, "r0");
        String leader = selector.selectLeader();
        assertThat(leader).isNotEqualTo("r" + down);
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"cn", "us", "eu", "default"})
    void compliancePolicyMatrix(String from) {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china", "us", "us"));
        ComplianceValidator validator = new ComplianceValidator();
        validator.validate(policy, from, from);
    }

    @ParameterizedTest(name = "pair {0}")
    @ValueSource(strings = {"cn:us", "us:cn", "cn:eu", "eu:cn"})
    void complianceViolationMatrix(String pair) {
        String[] parts = pair.split(":");
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china", "us", "us"));
        assertThatThrownBy(() -> new ComplianceValidator().validate(
                policy, parts[0], parts[1]))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void complianceSameDefaultAllowed() {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of());
        new ComplianceValidator().validate(policy, "a", "b");
    }
}
