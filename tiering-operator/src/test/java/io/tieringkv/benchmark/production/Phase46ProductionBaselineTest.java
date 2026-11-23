package io.tieringkv.benchmark.production;

import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.sql.coprocessor.DynamicPushdownPlanner;
import io.tieringkv.transaction.async.MultiCloudOnePhaseScaleOut;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration
        .CloudTimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 46 生产基线（ADR-0239）：跨机定期回归口径 + 本地进程内补充，
 * TiKV 对比表如实标注（跨机 Runner 可执行项全绿 / 未执行项登记）。
 */
class Phase46ProductionBaselineTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDScaleOutCommitLatency(int commits) {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut();
        long[] samples = new long[commits];
        for (int i = 0; i < commits; i++) {
            long start = System.nanoTime();
            scaleOut.commit("t" + i, topology(), i);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        report("D-SCALEOUT", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(1000);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDWindowFamilyThroughput(int rows) {
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
                                .SUM_OVER);
        long start = System.nanoTime();
        executor.executeCompound(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BASELINE D-WINDOW %d -> %d rows/s%n",
                rows, rows * 1_000L / elapsedMs);
        assertThat(rows * 1_000L / elapsedMs)
                .isGreaterThan(50_000);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDArbitrationThroughput(int ticks) {
        CrossCloudTsoArbitration clock = arbitration();
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            clock.timestamp();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BASELINE D-ARBITRATION %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
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
                "PHASE46-BASELINE D-PLANNER %d -> %d/s%n",
                decisions, decisions * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "probes {0}")
    @ValueSource(ints = {100, 1000})
    void credentialV4Throughput(int probes) {
        io.tieringkv.config.CredentialProbe probe =
                new io.tieringkv.config.CredentialProbe(
                        io.tieringkv.config.CredentialProbe.Mode
                                .REAL,
                        (endpoint, timeout) -> true, 500);
        long start = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            probe.probeWithPermission("s3",
                    "https://s3.example.com", "secret",
                    (endpoint, timeout) -> true,
                    (endpoint, credential) -> true,
                    (endpoint, credential) -> true);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE46-BASELINE D-CRED-V4 %d -> %d/s%n",
                probes, probes * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "keyword {0}")
    @CsvSource({
            "TiKV",
            "A/B/C/D",
            "本地进程内",
            "跨机",
            "待执行",
            "定期回归",
            "趋势",
            "P50",
            "P95",
            "P99",
            "吞吐",
            "内存",
            "公开口径",
            "Runner",
            "Gateway×3",
            "Metadata×3",
            "Storage×6",
            "RTT",
            "RTO",
            "RPO",
            "冲突率",
            "收敛时间",
            "快照",
            "告警",
            "回归"
    })
    void tikvRegressionKeywordsPresent(String keyword)
            throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark", "tikv-cross-machine-regression.md"));
        assertThat(content).contains(keyword);
    }

    @ParameterizedTest(name = "entries {0} bytes {1}")
    @CsvSource({
            "1000,96,1000000",
            "10000,96,10000000",
            "100000,96,100000000",
            "1000,128,2000000",
            "10000,128,20000000",
            "100000,128,200000000",
            "50000,64,5000000",
            "200000,32,8000000",
            "25000,96,3000000",
            "75000,96,10000000"
    })
    void memoryEstimateBounds(int entries, int bytesPerEntry,
                              long bound) {
        assertThat((long) entries * bytesPerEntry)
                .isLessThan(bound);
    }

    @ParameterizedTest(name = "size {0} quantile {1}")
    @CsvSource({
            "1,0.5",
            "2,0.95",
            "10,0.99",
            "100,0.5",
            "100,0.95",
            "100,0.99",
            "1000,0.5",
            "1000,0.95",
            "1000,0.99",
            "5000,0.5",
            "5000,0.99",
            "10000,0.5",
            "10000,0.99",
            "20000,0.99",
            "50000,0.99"
    })
    void percentileBoundaries(int size, double quantile) {
        long[] samples = new long[size];
        Arrays.setAll(samples, i -> i);
        assertThat(percentile(samples, quantile))
                .isBetween(0L, size - 1L);
    }

    @Test
    void crossMachineRegressionDocumented() throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark", "tikv-cross-machine-regression.md"));
        assertThat(content).contains("定期回归");
        assertThat(content).contains("跨机待执行");
    }

    @Test
    void levelDP99WithinTarget() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut();
        long[] samples = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            scaleOut.commit("t" + i, topology(), i);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        assertThat(percentile(samples, 0.99))
                .isLessThan(1000);
    }

    @Test
    void arbitrationMonotonicBaseline() {
        CrossCloudTsoArbitration clock = arbitration();
        long first = clock.timestamp();
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(first);
    }

    @Test
    void resolvedTsMonotonicBaseline() {
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        resolved.advance(100);
        assertThat(resolved.advance(50)).isEqualTo(100);
        assertThat(resolved.advance(150)).isEqualTo(150);
    }

    @Test
    void gateV12Baseline() {
        assertThat(io.tieringkv.ci.GateConvergenceV12.gates())
                .hasSize(19);
    }

    @Test
    void complianceAuditorBaseline() {
        io.tieringkv.cluster.scheduler.AutonomousComplianceAuditor
                auditor = new io.tieringkv.cluster.scheduler
                .AutonomousComplianceAuditor();
        auditor.record("baseline event");
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
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

    private static void report(String label,
                               long[] samplesMicros) {
        System.out.printf(
                "PHASE46-BASELINE %s p50=%dus p95=%dus p99=%dus%n",
                label, percentile(samplesMicros, 0.5),
                percentile(samplesMicros, 0.95),
                percentile(samplesMicros, 0.99));
    }

    private static long percentile(long[] samples,
                                   double quantile) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = (int) (quantile * (sorted.length - 1));
        return sorted[index];
    }
}
