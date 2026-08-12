package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.ci.GateConvergenceV9;
import io.tieringkv.cluster.scheduler.AutonomousPdScheduler;
import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.transaction.async.CrossRegionOnePhaseCommit;
import io.tieringkv.transaction.tso.TsoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Phase 43 基准（进程内口径）：新能力吞吐与延迟，跨机 Runner 待执行。 */
class Phase43BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void crossRegionCommitThroughput(int commits) {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", true);
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            commit.commit("t" + i, Set.of("r1", "r2"));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE43-BENCH CROSS-REGION %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10_000})
    void compoundCoprocessorThroughput(int rows) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER, Operator.PROJECT,
                                Operator.AGGREGATE),
                        "k0", "zz", 10);
        long start = System.nanoTime();
        executor.executeCompound(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE43-BENCH COMPOUND %d -> %d rows/s%n",
                rows, rows * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "batches {0}")
    @ValueSource(ints = {100, 1000})
    void tsoBatchThroughput(int batches) {
        TsoService tso = new TsoService();
        long start = System.nanoTime();
        for (int i = 0; i < batches; i++) {
            tso.allocate(10);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE43-BENCH TSO %d -> %d batches/s%n",
                batches, batches * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {100, 1000})
    void pdIntegrationThroughput(int rounds) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < 4; i++) {
            discovery.heartbeat(
                    new TopologyDiscovery.Heartbeat(
                            "n" + i, "r" + (i % 2),
                            "az-" + (i % 2), 0), 50);
        }
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        var integration = new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(10),
                autonomy, 100);
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("n0", 300L);
        loads.put("n1", 300L);
        loads.put("n2", 50L);
        loads.put("n3", 50L);
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            integration.planAndExecute(loads);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE43-BENCH PD-GLOBAL %d -> %d rounds/s%n",
                rounds, rounds * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "probes {0}")
    @ValueSource(ints = {100, 1000})
    void credentialProbeThroughput(int probes) {
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
                "PHASE43-BENCH CREDENTIAL %d -> %d probes/s%n",
                probes, probes * 1_000L / elapsedMs);
    }

    @Test
    void gateConvergenceLookupLatency() {
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            GateConvergenceV9.gate("TD-048");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE43-BENCH GATE-LOOKUP 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void crossRegionFallbackMix() {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", false);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            if (i % 2 == 0) {
                commit.commit("t" + i, Set.of("r1", "r2"));
            } else {
                commit.commitTwoPhase("t" + i,
                        Set.of("r1", "r2"));
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE43-BENCH FALLBACK-MIX 10000 -> %d ms%n",
                elapsedMs);
    }
}
