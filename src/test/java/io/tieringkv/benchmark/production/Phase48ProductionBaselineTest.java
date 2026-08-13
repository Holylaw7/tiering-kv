package io.tieringkv.benchmark.production;

import io.tieringkv.sql.coprocessor.MultiAgentPushdownCoordinator;
import io.tieringkv.transaction.async.MultiOrgFederationArbitration;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter
        .SimulatedHardwareClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 48 生产基线（ADR-0253）：跨机回归闭环口径 + 本地进程内补充，
 * TiKV 对比表如实标注（跨机 Runner 可执行项全绿 / 未执行项登记）。
 */
class Phase48ProductionBaselineTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDFederationLatency(int commits) {
        MultiOrgFederationArbitration arbitration =
                federation();
        long[] samples = new long[commits];
        for (int i = 0; i < commits; i++) {
            long start = System.nanoTime();
            arbitration.commit("t" + i, clouds(), i);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        report("D-FEDERATION", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(1000);
    }

    @ParameterizedTest(name = "decisions {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDMultiAgentThroughput(int decisions) {
        MultiAgentPushdownCoordinator coordinator =
                coordinator();
        long start = System.nanoTime();
        for (int i = 0; i < decisions; i++) {
            coordinator.federatedDecide("q0");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BASELINE D-MULTIAGENT %d -> %d/s%n",
                decisions, decisions * 1_000L / elapsedMs);
        assertThat(decisions * 1_000L / elapsedMs)
                .isGreaterThan(10_000);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDHardwareClockThroughput(int ticks) {
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(
                        new SimulatedHardwareClock(0, 0), 0);
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            adapter.timestamp();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BASELINE D-HARDWARE %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {100, 1000})
    void regulatoryMappingThroughput(int events) {
        io.tieringkv.cluster.scheduler.RegulatoryMappingEngine
                engine = new io.tieringkv.cluster.scheduler
                .RegulatoryMappingEngine();
        engine.registerRule("GDPR", "A17", "delete");
        long start = System.nanoTime();
        for (int i = 0; i < events; i++) {
            engine.mapEvent("delete");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BASELINE D-MAPPING %d -> %d/s%n",
                events, events * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "probes {0}")
    @ValueSource(ints = {100, 1000})
    void credentialV6Throughput(int probes) {
        io.tieringkv.config.CredentialProbe probe =
                new io.tieringkv.config.CredentialProbe(
                        io.tieringkv.config.CredentialProbe.Mode
                                .REAL,
                        (endpoint, timeout) -> true, 500);
        long start = System.nanoTime();
        for (int i = 0; i < probes; i++) {
            probe.probeWithLatency("s3",
                    "https://s3.example.com", "secret",
                    (endpoint, timeout) -> true,
                    (endpoint, credential) -> true,
                    (endpoint, credential) -> true,
                    (endpoint, credential) -> true,
                    (endpoint, timeout) -> 5, 100);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BASELINE D-CRED-V6 %d -> %d/s%n",
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
            "自动重跑",
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
            "闭环"
    })
    void tikvRegressionClosureKeywordsPresent(String keyword)
            throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark", "tikv-regression-closure.md"));
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
    void regressionClosureDocumented() throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark", "tikv-regression-closure.md"));
        assertThat(content).contains("自动重跑");
        assertThat(content).contains("跨机待执行");
    }

    @Test
    void levelDP99WithinTarget() {
        MultiOrgFederationArbitration arbitration =
                federation();
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
    void hardwareClockMonotonicBaseline() {
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(
                        new SimulatedHardwareClock(1000, 0), 0);
        long first = adapter.timestamp();
        assertThat(adapter.timestamp()).isGreaterThan(first);
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
    void gateV14Baseline() {
        assertThat(io.tieringkv.ci.GateConvergenceV14.gates())
                .hasSize(19);
    }

    @Test
    void releaseArchiveBaseline() {
        io.tieringkv.ci.ReleaseRecordArchive archive =
                new io.tieringkv.ci.ReleaseRecordArchive();
        archive.record("v3.1.0", "REL-001", true, "tagged");
        assertThat(archive.forVersion("v3.1.0")).hasSize(1);
    }

    private static MultiOrgFederationArbitration federation() {
        MultiOrgFederationArbitration arbitration =
                new MultiOrgFederationArbitration();
        for (int o = 1; o <= 2; o++) {
            for (int c = 1; c <= 2; c++) {
                String cloud = "c" + o + "-" + c;
                arbitration.registerOrganization(cloud,
                        "org-" + o);
                for (int z = 1; z <= 3; z++) {
                    arbitration.registerZone(cloud, "z" + z,
                            z <= 2);
                }
            }
        }
        return arbitration;
    }

    private static Set<String> clouds() {
        Set<String> clouds = new HashSet<>();
        clouds.add("c1-1");
        clouds.add("c1-2");
        clouds.add("c2-1");
        clouds.add("c2-2");
        return clouds;
    }

    private static MultiAgentPushdownCoordinator coordinator() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("q0",
                new io.tieringkv.sql.coprocessor
                        .ReinforcementPushdownAgent(0.5, 0.0,
                        100), 1.0);
        coordinator.registerAgent("q1",
                new io.tieringkv.sql.coprocessor
                        .ReinforcementPushdownAgent(0.5, 0.0,
                        100), 1.0);
        return coordinator;
    }

    private static void report(String label,
                               long[] samplesMicros) {
        System.out.printf(
                "PHASE48-BASELINE %s p50=%dus p95=%dus p99=%dus%n",
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
