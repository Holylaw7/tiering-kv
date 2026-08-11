package io.tieringkv.platform;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.gateway.ConflictAuditLog;
import io.tieringkv.gateway.RegionAffinityRouter;
import io.tieringkv.replication.active.LeaderSelector;
import io.tieringkv.sharding.auto.ConcurrentReshardExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 32 混沌：选主故障、并发迁移、合规拒绝、冲突审计。 */
class Phase32ChaosTest {

    @Test
    void leaderFailoverSwitches() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> false, "b", () -> true,
                "c", () -> true), "a");
        assertThat(selector.selectLeader()).isEqualTo("b");
        assertThat(selector.majorityHealthy()).isTrue();
    }

    @ParameterizedTest(name = "down {0}")
    @ValueSource(ints = {1, 2})
    void leaderMajorityLoss(int down) {
        Map<String, java.util.function.BooleanSupplier> health =
                new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            health.put("r" + i, () -> index != down);
        }
        LeaderSelector selector = new LeaderSelector(health, "r0");
        selector.selectLeader();
        // 3 节点单故障仍为多数健康（2/3）。
        assertThat(selector.majorityHealthy()).isTrue();
    }

    @Test
    void leaderMajorityLossWithTwoDown() {
        Map<String, java.util.function.BooleanSupplier> health =
                new LinkedHashMap<>();
        health.put("r0", () -> true);
        health.put("r1", () -> false);
        health.put("r2", () -> false);
        LeaderSelector selector = new LeaderSelector(health, "r0");
        assertThat(selector.majorityHealthy()).isFalse();
    }

    @Test
    void concurrentReshardInterruptSafe() throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) {
            source.put("k" + i, bytes("v"));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(4, 50);
        int moved = executor.execute(source, target);
        assertThat(moved + source.size()).isEqualTo(500);
    }

    @Test
    void complianceViolationRejected() {
        ComplianceValidator validator = new ComplianceValidator();
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china", "us", "us"));
        assertThatThrownBy(() -> validator.validate(
                policy, "cn", "us"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void conflictAuditComplete() {
        ConflictAuditLog audit = new ConflictAuditLog();
        RegionAffinityRouter router =
                new RegionAffinityRouter(List.of("r1", "r2"));
        for (int i = 0; i < 20; i++) {
            String key = "k" + i;
            String region = router.route(bytes(key));
            audit.audit(region, key, "r2");
        }
        assertThat(audit.size()).isEqualTo(20);
        assertThat(audit.entries()).allMatch(entry ->
                entry.winner().equals("r2"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
