package io.tieringkv.benchmark.mvcc;

import io.tieringkv.ci.GateConvergenceV12;
import io.tieringkv.cluster.scheduler.AutonomousComplianceAuditor;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.sql.coprocessor.DynamicPushdownPlanner;
import io.tieringkv.transaction.async.MultiCloudOnePhaseScaleOut;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration
        .CloudTimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Phase 46 基准（进程内口径）：规模化/窗口全族/仲裁/合规吞吐。 */
class Phase46BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void scaleOutCommitThroughput(int commits) {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut();
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            scaleOut.commit("t" + i, topology(), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH SCALEOUT %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10_000})
    void windowFamilyThroughput(int rows) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + (i % 5), i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW),
                        "k0", "zz", 0, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .AVG_OVER);
        long start = System.nanoTime();
        executor.executeCompound(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH WINDOW-FAMILY %d -> %d rows/s%n",
                rows, rows * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 10_000})
    void arbitrationThroughput(int ticks) {
        CrossCloudTsoArbitration clock = arbitration();
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            clock.timestamp();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH ARBITRATION %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {1000, 10_000})
    void complianceThroughput(int records) {
        AutonomousComplianceAuditor auditor =
                new AutonomousComplianceAuditor();
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            auditor.record("event " + i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH COMPLIANCE %d -> %d/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "decisions {0}")
    @ValueSource(ints = {1000, 10_000})
    void dynamicPlannerThroughput(int decisions) {
        DynamicPushdownPlanner planner =
                new DynamicPushdownPlanner(0.5, 1);
        planner.record(100, 1000, 1000);
        long start = System.nanoTime();
        for (int i = 0; i < decisions; i++) {
            planner.shouldPushdown(i, 100, 10);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH PLANNER %d -> %d/s%n",
                decisions, decisions * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "lookups {0}")
    @ValueSource(ints = {1000, 10_000})
    void gateLookupThroughput(int lookups) {
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            GateConvergenceV12.gate("TD-048");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH GATE-V12 %d -> %d/s%n",
                lookups, lookups * 1_000L / elapsedMs);
    }

    @Test
    void rollbackProtectionLatency() {
        CrossCloudTsoArbitration clock = arbitration();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            clock.timestamp();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH ROLLBACK-GUARD 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void complianceVerifyLatency() {
        AutonomousComplianceAuditor auditor =
                new AutonomousComplianceAuditor();
        for (int i = 0; i < 1000; i++) {
            auditor.record("event " + i);
        }
        List<String> exported = auditor.exportAudit();
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            auditor.verify(exported);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH COMPLIANCE-VERIFY 100 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void scaleOutFallbackMixLatency() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            scaleOut.commit("t" + i, topology(), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BENCH SCALEOUT-MIX 10000 -> %d ms%n",
                elapsedMs);
    }

    private static MultiCloudOnePhaseScaleOut scaleOut() {
        MultiCloudOnePhaseScaleOut scaleOut =
                new MultiCloudOnePhaseScaleOut();
        for (int c = 1; c <= 3; c++) {
            for (int z = 1; z <= 3; z++) {
                scaleOut.registerZone("c" + c, "z" + z,
                        z <= 2);
            }
        }
        return scaleOut;
    }

    private static Map<String, Set<String>> topology() {
        Map<String, Set<String>> topology =
                new LinkedHashMap<>();
        for (int c = 1; c <= 3; c++) {
            Set<String> zones = new HashSet<>();
            for (int z = 1; z <= 3; z++) {
                zones.add("z" + z);
            }
            topology.put("c" + c, zones);
        }
        return topology;
    }

    private static CrossCloudTsoArbitration arbitration() {
        return new CrossCloudTsoArbitration(
                List.of(new CloudTimeSource("aws", 0),
                        new CloudTimeSource("gcp", 0),
                        new CloudTimeSource("azure", 0)),
                100, 1000);
    }
}
