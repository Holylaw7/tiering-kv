package io.tieringkv.platform;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.gateway.ConflictAuditLog;
import io.tieringkv.gateway.RegionAffinityRouter;
import io.tieringkv.replication.active.LeaderSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 32 最终边缘矩阵。 */
class Phase32FinalEdgeTest {

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"", "a", "user:1", "中文"})
    void affinityKeyBoundaries(String key) {
        RegionAffinityRouter router =
                new RegionAffinityRouter(List.of("r1", "r2"));
        assertThat(router.route(bytes(key))).isIn("r1", "r2");
    }

    @ParameterizedTest(name = "winner {0}")
    @ValueSource(strings = {"r1", "r2", "r3"})
    void auditWinnerBoundaries(String winner) {
        ConflictAuditLog audit = new ConflictAuditLog();
        audit.audit("r1", "k", winner);
        assertThat(audit.entries().get(0).winner()).isEqualTo(winner);
    }

    @ParameterizedTest(name = "leader {0}")
    @ValueSource(strings = {"a", "b", "c"})
    void leaderInitialBoundaries(String initial) {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> true, "b", () -> true,
                "c", () -> true), initial);
        assertThat(selector.selectLeader()).isEqualTo(initial);
    }

    @ParameterizedTest(name = "region {0}")
    @ValueSource(strings = {"cn", "us", "eu", "ap", "sa"})
    void residencyPolicyBoundaries(String region) {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china", "us", "us"));
        assertThat(policy.required(region)).isNotBlank();
    }

    @Test
    void complianceSameRegionAllowed() {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china"));
        new ComplianceValidator().validate(policy, "cn", "cn");
    }

    @Test
    void complianceUnknownViolationRejected() {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china"));
        assertThatThrownBy(() -> new ComplianceValidator().validate(
                policy, "unknown", "cn"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void auditKeyFilter() {
        ConflictAuditLog audit = new ConflictAuditLog();
        audit.audit("r1", "k1", "r2");
        audit.audit("r1", "k2", "r2");
        assertThat(audit.byKey("k1")).hasSize(1);
    }

    @Test
    void leaderMajorityBoundary() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> true, "b", () -> true,
                "c", () -> false), "a");
        assertThat(selector.majorityHealthy()).isTrue();
    }

    @Test
    void affinityLongKeyBoundary() {
        RegionAffinityRouter router =
                new RegionAffinityRouter(List.of("r1", "r2"));
        assertThat(router.route(bytes("k".repeat(64))))
                .isIn("r1", "r2");
    }

    @Test
    void auditEntriesImmutableCopy() {
        ConflictAuditLog audit = new ConflictAuditLog();
        audit.audit("r1", "k", "r2");
        assertThat(audit.entries()).isNotSameAs(audit.entries());
    }

    @Test
    void complianceEmptyPolicyAllowsAll() {
        new ComplianceValidator().validate(
                new DataResidencyPolicy(Map.of()), "a", "b");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
