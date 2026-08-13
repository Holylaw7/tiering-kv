package io.tieringkv.benchmark.mvcc;

import io.tieringkv.ci.GateConvergenceV14;
import io.tieringkv.cluster.scheduler.RegulatoryMappingEngine;
import io.tieringkv.sql.coprocessor.MultiAgentPushdownCoordinator;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.MultiOrgFederationArbitration;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter
        .SimulatedHardwareClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

/** Phase 48 基准（进程内口径）：联邦/多智能体/硬件/法规映射吞吐。 */
class Phase48BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void federationThroughput(int commits) {
        MultiOrgFederationArbitration arbitration =
                federation();
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            arbitration.commit("t" + i, clouds(), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH FEDERATION %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "decisions {0}")
    @ValueSource(ints = {1000, 10_000})
    void multiAgentThroughput(int decisions) {
        MultiAgentPushdownCoordinator coordinator =
                coordinator();
        long start = System.nanoTime();
        for (int i = 0; i < decisions; i++) {
            coordinator.federatedDecide("q0");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH MULTIAGENT %d -> %d/s%n",
                decisions, decisions * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 10_000})
    void hardwareAdapterThroughput(int ticks) {
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
                "PHASE48-BENCH HARDWARE %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {100, 1000})
    void regulatoryMappingThroughput(int events) {
        RegulatoryMappingEngine engine =
                new RegulatoryMappingEngine();
        engine.registerRule("GDPR", "A17", "delete");
        long start = System.nanoTime();
        for (int i = 0; i < events; i++) {
            engine.mapEvent("delete");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH MAPPING %d -> %d/s%n",
                events, events * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "lookups {0}")
    @ValueSource(ints = {1000, 10_000})
    void gateLookupThroughput(int lookups) {
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            GateConvergenceV14.gate("TD-048");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH GATE-V14 %d -> %d/s%n",
                lookups, lookups * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {100, 1000})
    void releaseArchiveThroughput(int records) {
        io.tieringkv.ci.ReleaseRecordArchive archive =
                new io.tieringkv.ci.ReleaseRecordArchive();
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            archive.record("v3.1.0", "REL-001", true,
                    "tagged");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH RELEASE-ARCHIVE %d -> %d/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @Test
    void federationFallbackMixLatency() {
        MultiOrgFederationArbitration arbitration =
                federation();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            if (i % 3 == 0) {
                arbitration.commit("t" + i,
                        Set.of("c1-1", "c9-9"));
            } else {
                arbitration.commit("t" + i, clouds());
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH FEDERATION-MIX 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void multiAgentLearningLatency() {
        MultiAgentPushdownCoordinator coordinator =
                coordinator();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            coordinator.learn("q0",
                    ReinforcementPushdownAgent.Action.PUSHDOWN,
                    i % 2 == 0 ? 5 : -1);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH MULTIAGENT-LEARN 10000 -> %d ms%n",
                elapsedMs);
    }

    @Test
    void hardwareFailoverLatency() {
        SimulatedHardwareClock clock =
                new SimulatedHardwareClock(1000, 0);
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(clock, 0);
        long start = System.nanoTime();
        clock.fail();
        for (int i = 0; i < 1000; i++) {
            adapter.timestamp();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE48-BENCH HW-FAILOVER 1000 -> %d ms%n",
                elapsedMs);
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
                new ReinforcementPushdownAgent(0.5, 0.0, 100),
                1.0);
        coordinator.registerAgent("q1",
                new ReinforcementPushdownAgent(0.5, 0.0, 100),
                1.0);
        return coordinator;
    }
}
