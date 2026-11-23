package io.tieringkv.benchmark.mvcc;

import io.tieringkv.ci.GateConvergenceV11;
import io.tieringkv.cluster.scheduler.AutonomousPdUnattended;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.sql.coprocessor.PushdownCostModel;
import io.tieringkv.transaction.async.MultiCloudOnePhaseCommit;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.GlobalTsoClock;
import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSource;
import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Phase 45 基准（进程内口径）：跨云/窗口/时钟/无人值守吞吐。 */
class Phase45BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void multiCloudCommitThroughput(int commits) {
        MultiCloudOnePhaseCommit commit = cloudCommit();
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            commit.commit("t" + i,
                    Set.of("aws", "gcp", "azure"), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH MULTICLOUD %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10_000})
    void windowChainThroughput(int rows) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + (i % 5), i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.JOIN, Operator.WINDOW,
                                Operator.ORDER_BY, Operator.LIMIT),
                        "k0", "zz", 0,
                        List.of(new Row("k0", 1),
                                new Row("k1", 1)),
                        List.of(), 10, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .ROW_NUMBER);
        long start = System.nanoTime();
        executor.executeCompound(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH WINDOW %d -> %d rows/s%n",
                rows, rows * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 10_000})
    void globalClockThroughput(int ticks) {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(new TimeSource(TimeSourceType.GPS, 0),
                        new TimeSource(TimeSourceType.ATOMIC, 0),
                        new TimeSource(TimeSourceType.NTP, 0)),
                100);
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            clock.timestamp();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH GLOBAL-CLOCK %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {100, 1000})
    void unattendedThroughput(int rounds) {
        AutonomousPdUnattended unattended =
                io.tieringkv.platform.Phase45ProductionGateTest
                        .unattendedHelper();
        var loads = io.tieringkv.platform.Phase45ProductionGateTest
                .loadsHelper();
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            unattended.execute(loads, 100);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH UNATTENDED %d -> %d/s%n",
                rounds, rounds * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "probes {0}")
    @ValueSource(ints = {100, 1000})
    void credentialV3Throughput(int probes) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
        long start = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            probe.probeAuthenticated("s3",
                    "https://s3.example.com", "secret",
                    (endpoint, timeout) -> true,
                    (endpoint, credential) -> true);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH CRED-V3 %d -> %d/s%n",
                probes, probes * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "decisions {0}")
    @ValueSource(ints = {1000, 10_000})
    void costModelThroughput(int decisions) {
        PushdownCostModel model = new PushdownCostModel(0);
        long start = System.nanoTime();
        for (int i = 0; i < decisions; i++) {
            model.shouldPushdown(i, 100, 10);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH COST-MODEL %d -> %d/s%n",
                decisions, decisions * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "lookups {0}")
    @ValueSource(ints = {1000, 10_000})
    void gateLookupThroughput(int lookups) {
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            GateConvergenceV11.gate("TD-048");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH GATE-V11 %d -> %d/s%n",
                lookups, lookups * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1000, 10_000})
    void resolvedTsMixThroughput(int txns) {
        MultiCloudOnePhaseCommit commit = cloudCommit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        long start = System.nanoTime();
        for (int i = 0; i < txns; i++) {
            commit.commit("t" + i,
                    Set.of("aws", "gcp", "azure"), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH RESOLVED-MIX %d -> %d ops/s%n",
                txns, txns * 1_000L / elapsedMs);
    }

    @Test
    void multiCloudFallbackMixLatency() {
        MultiCloudOnePhaseCommit commit = cloudCommit();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            if (i % 3 == 0) {
                commit.commit("t" + i,
                        Set.of("aws", "ali"));
            } else {
                commit.commit("t" + i,
                        Set.of("aws", "gcp", "azure"));
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH FALLBACK-MIX 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void windowRankLatency() {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            data.add(new Row("k" + (i % 5), i % 10));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW),
                        "k0", "zz", 0, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .RANK);
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            executor.executeCompound(request, data);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH RANK 1000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void clockRestoreLatency() {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(new TimeSource(TimeSourceType.GPS, 0),
                        new TimeSource(TimeSourceType.NTP, 0)),
                100);
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            clock.restore(i);
        }
        long elapsedUs = Math.max(1,
                (System.nanoTime() - start) / 1_000);
        System.out.printf(
                "PHASE45-BENCH CLOCK-RESTORE 1000 -> %d us%n",
                elapsedUs);
    }

    @Test
    void costModelDecisionLatency() {
        PushdownCostModel model = new PushdownCostModel(100);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            model.shouldPushdown(i, 100, 10);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BENCH COST 10000 -> %d ms%n",
                elapsedMs);
    }

    private static MultiCloudOnePhaseCommit cloudCommit() {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        commit.registerCloud("aws", true);
        commit.registerCloud("gcp", true);
        commit.registerCloud("azure", false);
        commit.registerCloud("ali", false);
        return commit;
    }
}
