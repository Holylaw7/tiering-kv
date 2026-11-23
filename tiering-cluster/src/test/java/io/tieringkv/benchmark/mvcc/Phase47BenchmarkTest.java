package io.tieringkv.benchmark.mvcc;

import io.tieringkv.ci.GateConvergenceV13;
import io.tieringkv.cluster.scheduler.RegulatoryComplianceCertificate;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.GlobalUnifiedOnePhaseArbitration;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource
        .SourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

/** Phase 47 基准（进程内口径）：统一仲裁/RL/量子授时/监管证书吞吐。 */
class Phase47BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void unifiedArbitrationThroughput(int commits) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration();
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            arbitration.commit("t" + i, clouds(), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH UNIFIED %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "decisions {0}")
    @ValueSource(ints = {1000, 10_000})
    void rlAgentThroughput(int decisions) {
        ReinforcementPushdownAgent agent =
                new ReinforcementPushdownAgent(0.5, 0.1, 100);
        long start = System.nanoTime();
        for (int i = 0; i < decisions; i++) {
            agent.decide();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH RL %d -> %d/s%n",
                decisions, decisions * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 10_000})
    void quantumClockThroughput(int ticks) {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.QUANTUM, 5);
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            source.timestamp(i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH QUANTUM %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "certificates {0}")
    @ValueSource(ints = {100, 1000})
    void regulatoryCertThroughput(int certificates) {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        long start = System.nanoTime();
        for (int i = 0; i < certificates; i++) {
            cert.issue("digest-" + i, "auditor");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH CERT %d -> %d/s%n",
                certificates,
                certificates * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "lookups {0}")
    @ValueSource(ints = {1000, 10_000})
    void gateLookupThroughput(int lookups) {
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            GateConvergenceV13.gate("TD-048");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH GATE-V13 %d -> %d/s%n",
                lookups, lookups * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {100, 1000})
    void runnerArchiveThroughput(int records) {
        io.tieringkv.ci.RunnerExecutionArchive archive =
                new io.tieringkv.ci.RunnerExecutionArchive();
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            archive.record("TD-048", true, "evidence");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH ARCHIVE %d -> %d/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @Test
    void unifiedFallbackMixLatency() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            if (i % 3 == 0) {
                arbitration.commit("t" + i,
                        Set.of("c1", "c9", "c10"));
            } else {
                arbitration.commit("t" + i, clouds());
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH UNIFIED-MIX 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void rlLearningLatency() {
        ReinforcementPushdownAgent agent =
                new ReinforcementPushdownAgent(0.5, 0.1, 100);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            agent.learn(
                    ReinforcementPushdownAgent.Action.PUSHDOWN,
                    i % 2 == 0 ? 5 : -1);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BENCH RL-LEARN 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void quantumRestoreLatency() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.SATELLITE, 10);
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            source.restore(i);
        }
        long elapsedUs = Math.max(1,
                (System.nanoTime() - start) / 1_000);
        System.out.printf(
                "PHASE47-BENCH QUANTUM-RESTORE 1000 -> %d us%n",
                elapsedUs);
    }

    private static GlobalUnifiedOnePhaseArbitration arbitration() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                new GlobalUnifiedOnePhaseArbitration();
        for (int c = 1; c <= 3; c++) {
            for (int z = 1; z <= 3; z++) {
                arbitration.registerZone("c" + c, "z" + z,
                        z <= 2);
            }
        }
        return arbitration;
    }

    private static Set<String> clouds() {
        Set<String> clouds = new HashSet<>();
        clouds.add("c1");
        clouds.add("c2");
        clouds.add("c3");
        return clouds;
    }
}
