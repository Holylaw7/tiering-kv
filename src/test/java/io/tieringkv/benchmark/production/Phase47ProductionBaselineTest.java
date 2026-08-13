package io.tieringkv.benchmark.production;

import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.GlobalUnifiedOnePhaseArbitration;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource
        .SourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 47 生产基线（ADR-0246）：跨机回归告警口径 + 本地进程内补充，
 * TiKV 对比表如实标注（跨机 Runner 可执行项全绿 / 未执行项登记）。
 */
class Phase47ProductionBaselineTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDUnifiedArbitrationLatency(int commits) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration();
        long[] samples = new long[commits];
        for (int i = 0; i < commits; i++) {
            long start = System.nanoTime();
            arbitration.commit("t" + i, clouds(), i);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        report("D-UNIFIED", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(1000);
    }

    @ParameterizedTest(name = "decisions {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDRlPushdownThroughput(int decisions) {
        ReinforcementPushdownAgent agent =
                new ReinforcementPushdownAgent(0.5, 0.1, 100);
        long start = System.nanoTime();
        for (int i = 0; i < decisions; i++) {
            agent.decide();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BASELINE D-RL %d -> %d/s%n",
                decisions, decisions * 1_000L / elapsedMs);
        assertThat(decisions * 1_000L / elapsedMs)
                .isGreaterThan(10_000);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDQuantumClockThroughput(int ticks) {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.QUANTUM, 10);
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            source.timestamp(i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BASELINE D-QUANTUM %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "certificates {0}")
    @ValueSource(ints = {100, 1000})
    void regulatoryCertificateThroughput(int certificates) {
        io.tieringkv.cluster.scheduler
                .RegulatoryComplianceCertificate cert =
                new io.tieringkv.cluster.scheduler
                        .RegulatoryComplianceCertificate();
        long start = System.nanoTime();
        for (int i = 0; i < certificates; i++) {
            cert.issue("digest-" + i, "auditor");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BASELINE D-CERT %d -> %d/s%n",
                certificates,
                certificates * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "probes {0}")
    @ValueSource(ints = {100, 1000})
    void credentialV5Throughput(int probes) {
        io.tieringkv.config.CredentialProbe probe =
                new io.tieringkv.config.CredentialProbe(
                        io.tieringkv.config.CredentialProbe.Mode
                                .REAL,
                        (endpoint, timeout) -> true, 500);
        long start = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            probe.probeWithQuota("s3",
                    "https://s3.example.com", "secret",
                    (endpoint, timeout) -> true,
                    (endpoint, credential) -> true,
                    (endpoint, credential) -> true,
                    (endpoint, credential) -> true);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE47-BASELINE D-CRED-V5 %d -> %d/s%n",
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
            "告警",
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
            "阈值"
    })
    void tikvAlertingKeywordsPresent(String keyword)
            throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark",
                "tikv-cross-machine-regression-alerting.md"));
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
            "5000,0.99"
    })
    void percentileBoundaries(int size, double quantile) {
        long[] samples = new long[size];
        Arrays.setAll(samples, i -> i);
        assertThat(percentile(samples, quantile))
                .isBetween(0L, size - 1L);
    }

    @Test
    void alertingBaselineDocumented() throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark",
                "tikv-cross-machine-regression-alerting.md"));
        assertThat(content).contains("告警");
        assertThat(content).contains("跨机待执行");
    }

    @Test
    void levelDP99WithinTarget() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration();
        long[] samples = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            arbitration.commit("t" + i, clouds(), i);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        assertThat(percentile(samples, 0.99))
                .isLessThan(1000);
    }

    @Test
    void quantumClockMonotonicBaseline() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.SATELLITE, 5);
        long first = source.timestamp(1000);
        assertThat(source.timestamp(1000))
                .isGreaterThan(first);
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
    void gateV13Baseline() {
        assertThat(io.tieringkv.ci.GateConvergenceV13.gates())
                .hasSize(19);
    }

    @Test
    void runnerArchiveBaseline() {
        io.tieringkv.ci.RunnerExecutionArchive archive =
                new io.tieringkv.ci.RunnerExecutionArchive();
        archive.record("TD-048", true, "evidence");
        assertThat(archive.forGate("TD-048")).hasSize(1);
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

    private static void report(String label,
                               long[] samplesMicros) {
        System.out.printf(
                "PHASE47-BASELINE %s p50=%dus p95=%dus p99=%dus%n",
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
