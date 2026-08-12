package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.ci.GateConvergenceV10;
import io.tieringkv.cluster.scheduler.AutonomousPdFullAutomation;
import io.tieringkv.cluster.scheduler.AutonomousPdScheduler;
import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.transaction.async.GlobalOnePhaseCommit;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.TsoDisasterRecovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Phase 44 基准（进程内口径）：新能力吞吐与延迟。 */
class Phase44BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void globalCommitThroughput(int commits) {
        GlobalOnePhaseCommit commit = globalCommit();
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            commit.commit("t" + i, Set.of("r1", "r2", "r3"),
                    i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH GLOBAL-COMMIT %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10_000})
    void fullOperatorThroughput(int rows) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + (i % 10), i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.GROUP_BY,
                                Operator.ORDER_BY,
                                Operator.LIMIT),
                        "k0", "zz", 0, List.of(), 10,
                        false);
        long start = System.nanoTime();
        executor.executeCompound(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH FULL-OP %d -> %d rows/s%n",
                rows, rows * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {100, 1000})
    void tsoDrThroughput(int rounds) {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            recovery.allocate(10);
        }
        recovery.failover();
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH TSO-DR %d -> %d batches/s%n",
                rounds, rounds * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {100, 1000})
    void autoPdThroughput(int rounds) {
        AutonomousPdFullAutomation automation =
                autoAutomation(3);
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("n0", 300L);
        loads.put("n1", 50L);
        loads.put("n2", 50L);
        loads.put("n3", 50L);
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            automation.execute(loads, 100);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH AUTO-PD %d -> %d rounds/s%n",
                rounds, rounds * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "probes {0}")
    @ValueSource(ints = {100, 1000})
    void credentialThroughput(int probes) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.SIMULATED,
                (endpoint, timeout) -> true, 1000);
        long start = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            probe.probe("s3", "https://s3.example.com",
                    "AKIA-TEST");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH CRED %d -> %d probes/s%n",
                probes, probes * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "lookups {0}")
    @ValueSource(ints = {1000, 10_000})
    void gateLookupThroughput(int lookups) {
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            GateConvergenceV10.gate("TD-048");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH GATE-LOOKUP %d -> %d/s%n",
                lookups, lookups * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1000, 10_000})
    void resolvedTsMixThroughput(int txns) {
        GlobalOnePhaseCommit commit = globalCommit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        long start = System.nanoTime();
        for (int i = 0; i < txns; i++) {
            commit.commit("t" + i,
                    Set.of("r1", "r2", "r3"), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH RESOLVED-MIX %d -> %d ops/s%n",
                txns, txns * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void tikvBaselineSmoke(int ops) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < ops; i++) {
            data.add(new Row("k" + i, i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER,
                                Operator.GROUP_BY),
                        "k0", "zz", ops / 2, List.of(),
                        Integer.MAX_VALUE, false);
        long start = System.nanoTime();
        executor.executeCompound(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH TIKV-SMOKE %d -> %d rows/s%n",
                ops, ops * 1_000L / elapsedMs);
    }

    @Test
    void globalFallbackMixLatency() {
        GlobalOnePhaseCommit commit = globalCommit();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            if (i % 3 == 0) {
                commit.commit("t" + i, Set.of("r1", "r3"));
            } else {
                commit.commit("t" + i,
                        Set.of("r1", "r2", "r3"));
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH FALLBACK-MIX 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void fullOpChainLatency() {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            data.add(new Row("k" + (i % 5), i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.JOIN, Operator.FILTER,
                                Operator.PROJECT, Operator.GROUP_BY,
                                Operator.ORDER_BY, Operator.LIMIT),
                        "k0", "zz", 100,
                        List.of(new Row("k0", 1),
                                new Row("k1", 1)),
                        5, false);
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            executor.executeCompound(request, data);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH FULL-OP-CHAIN 1000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void tsoFailoverLatency() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.allocate(1000);
        long start = System.nanoTime();
        recovery.failover();
        long elapsedUs = Math.max(1,
                (System.nanoTime() - start) / 1_000);
        System.out.printf(
                "PHASE44-BENCH TSO-FAILOVER -> %d us%n",
                elapsedUs);
    }

    @Test
    void autoPdAuditLatency() {
        AutonomousPdFullAutomation automation =
                autoAutomation(3);
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("n0", 300L);
        loads.put("n1", 50L);
        loads.put("n2", 50L);
        loads.put("n3", 50L);
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            automation.execute(loads, 100);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE44-BENCH AUTO-AUDIT 1000 -> %d ms%n",
                elapsedMs);
    }

    private static GlobalOnePhaseCommit globalCommit() {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", true);
        commit.registerPrimaryReplica("r3", false);
        return commit;
    }

    private static AutonomousPdFullAutomation autoAutomation(
            int lowRiskMaxMoves) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < 8; i++) {
            discovery.heartbeat(
                    new TopologyDiscovery.Heartbeat(
                            "n" + i, "r" + (i % 2),
                            "az-" + (i % 2), 0), 50);
        }
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        return new AutonomousPdFullAutomation(
                new GlobalAutonomyPdIntegration(discovery,
                        new AutonomousPdScheduler(10),
                        autonomy, 100), lowRiskMaxMoves);
    }
}
