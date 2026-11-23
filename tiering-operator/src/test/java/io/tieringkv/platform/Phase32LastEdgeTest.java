package io.tieringkv.platform;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.gateway.ConflictAuditLog;
import io.tieringkv.gateway.RegionAffinityRouter;
import io.tieringkv.replication.active.LeaderSelector;
import io.tieringkv.sharding.auto.ConcurrentReshardExecutor;
import io.tieringkv.sql.txn.SqlTxn2PcExecutor;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 32 收尾边缘矩阵。 */
class Phase32LastEdgeTest {

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20, 50, 100, 200, 500,
            1000, 2000, 5000, 10000, 20000, 50000})
    void sql2pcWriteMatrix(int writes) {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.WRITER, 60_000);
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        executor.begin(token);
        for (int i = 0; i < writes; i++) {
            executor.write(bytes("k" + i), bytes("v"), false);
        }
        assertThat(executor.commit()).isTrue();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20, 50, 100, 200, 500,
            1000, 2000, 5000, 10000, 20000, 50000})
    void concurrentReshardMatrix(int count) throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v"));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(2, 25);
        assertThat(executor.execute(source, target)).isEqualTo(count);
        assertThat(target).hasSize(count);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 8, 10, 12, 16, 20,
            25, 32, 50, 64, 100})
    void affinityMatrix(int count) {
        List<String> regions = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            regions.add("r" + i);
        }
        RegionAffinityRouter router = new RegionAffinityRouter(regions);
        assertThat(router.route(bytes("k"))).isIn(regions);
    }

    @Test
    void leaderMajorityTie() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> true, "b", () -> false,
                "c", () -> false), "a");
        assertThat(selector.majorityHealthy()).isFalse();
    }

    @Test
    void complianceCaseSensitive() {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china"));
        new ComplianceValidator().validate(policy, "cn", "cn");
        assertThat(policy.required("CN")).isEqualTo("default");
    }

    @Test
    void auditOrderPreserved() {
        ConflictAuditLog audit = new ConflictAuditLog();
        audit.audit("r1", "k1", "w1");
        audit.audit("r1", "k2", "w2");
        assertThat(audit.entries().get(0).winner()).isEqualTo("w1");
        assertThat(audit.entries().get(1).winner()).isEqualTo("w2");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
