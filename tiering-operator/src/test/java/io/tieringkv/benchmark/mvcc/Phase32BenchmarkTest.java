package io.tieringkv.benchmark.mvcc;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
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

/** Phase 32 基准（进程内口径，如实记录）。 */
class Phase32BenchmarkTest {

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {100, 1000})
    void sql2pcProductionThroughput(int txns) {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.WRITER, 60_000);
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        long start = System.nanoTime();
        for (int i = 0; i < txns; i++) {
            executor.begin(token);
            executor.write(bytes("k" + i), bytes("v"), false);
            executor.commit();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE32-BENCH SQL2PC-PROD %d -> %d txn/s%n",
                txns, txns * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1000, 10000})
    void concurrentReshardThroughput(int count) throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v"));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(4, 500);
        long start = System.nanoTime();
        assertThat(executor.execute(source, target)).isEqualTo(count);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE32-BENCH CONCURRENT-RESHARD %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "routes {0}")
    @ValueSource(ints = {1000, 10000})
    void affinityRouteThroughput(int count) {
        RegionAffinityRouter router =
                new RegionAffinityRouter(List.of("r1", "r2", "r3"));
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            router.route(bytes("k" + i));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE32-BENCH AFFINITY %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "selections {0}")
    @ValueSource(ints = {1000, 10000})
    void leaderSelectionThroughput(int count) {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> true, "b", () -> true), "a");
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            selector.selectLeader();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE32-BENCH LEADER %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @Test
    void complianceValidateLatency() {
        ComplianceValidator validator = new ComplianceValidator();
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "cn", "china", "us", "us"));
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            validator.validate(policy, "cn", "cn");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE32-BENCH COMPLIANCE %d ms%n", elapsedMs);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
