package io.tieringkv.benchmark.mvcc;

import io.tieringkv.ci.GateConvergenceV15;
import io.tieringkv.cluster.scheduler.RegulatoryKnowledgeBase;
import io.tieringkv.sql.coprocessor.FederatedPushdownLearning;
import io.tieringkv.sql.coprocessor.MultiAgentPushdownCoordinator;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.CrossRegulatoryFederationArbitration;
import io.tieringkv.transaction.async.MultiOrgFederationArbitration;
import io.tieringkv.transaction.tso.CommercialTimeDeviceConnector;
import io.tieringkv.transaction.tso.CommercialTimeDeviceConnector
        .SimulatedTimeDevice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

/** Phase 49 基准（进程内口径）：联邦/联邦学习/设备/法规库/门禁吞吐。 */
class Phase49BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 5000, 10_000, 50_000, 100_000})
    void crossRegulatoryThroughput(int commits) {
        CrossRegulatoryFederationArbitration arbitration =
                arbitration();
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            arbitration.commit("t" + i,
                    Set.of("cloud-a1", "cloud-b1"), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE49-BENCH CROSS-REG %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "learns {0}")
    @ValueSource(ints = {1000, 5000, 10_000, 50_000, 100_000})
    void federatedLearningThroughput(int learns) {
        FederatedPushdownLearning learning =
                new FederatedPushdownLearning(0.1, 1.0);
        for (int i = 0; i < 4; i++) {
            learning.registerAgent("q" + i,
                    new ReinforcementPushdownAgent(1.0, 0.0,
                            100.0), 1.0);
        }
        long start = System.nanoTime();
        for (int i = 0; i < learns; i++) {
            learning.federatedLearn("q" + (i % 4),
                    ReinforcementPushdownAgent.Action.PUSHDOWN,
                    1);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE49-BENCH FEDLEARN %d -> %d/s%n",
                learns, learns * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 5000, 10_000, 50_000, 100_000})
    void deviceConnectorThroughput(int ticks) {
        CommercialTimeDeviceConnector connector =
                new CommercialTimeDeviceConnector("a", "b", 10);
        connector.registerDriver(new SimulatedTimeDevice(
                "a", 1000, 0));
        connector.registerDriver(new SimulatedTimeDevice(
                "b", 2000, 0));
        connector.connect("a");
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            connector.timestamp();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE49-BENCH DEVICE %d -> %d/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "diffs {0}")
    @ValueSource(ints = {100, 1000, 5000, 10_000, 50_000})
    void regulatoryDiffThroughput(int diffs) {
        RegulatoryKnowledgeBase base =
                new RegulatoryKnowledgeBase();
        base.registerVersion("GDPR", "v1",
                List.of("A17", "A5", "A32"));
        base.registerVersion("GDPR", "v2",
                List.of("A17", "A5", "A32", "A33"));
        long start = System.nanoTime();
        for (int i = 0; i < diffs; i++) {
            base.diff("GDPR", "v1", "v2");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE49-BENCH REGDIFF %d -> %d/s%n",
                diffs, diffs * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "lookups {0}")
    @ValueSource(ints = {1000, 5000, 10_000, 50_000, 100_000})
    void gateLookupThroughput(int lookups) {
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            GateConvergenceV15.gate(GateConvergenceV15.gates()
                    .get(i % GateConvergenceV15.gates().size())
                    .id());
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE49-BENCH GATE %d -> %d/s%n",
                lookups, lookups * 1_000L / elapsedMs);
    }

    @Test
    void coordinatorIntegrationSmoke() {
        MultiOrgFederationArbitration multiOrg =
                new MultiOrgFederationArbitration();
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("q0",
                new ReinforcementPushdownAgent(1.0, 0.0, 100),
                1.0);
        assertThatCoordinatorWorks(coordinator);
        assertThatFederationWorks(multiOrg);
    }

    @Test
    void archiveIntegrationSmoke() {
        io.tieringkv.ci.RunnerClosureArchive archive =
                new io.tieringkv.ci.RunnerClosureArchive();
        archive.record("TD-081", "Phase 49",
                GateConvergenceV15.Disposition.CLOSED, "jvm");
        assertThatArchiveWorks(archive);
    }

    private static CrossRegulatoryFederationArbitration
    arbitration() {
        CrossRegulatoryFederationArbitration arbitration =
                new CrossRegulatoryFederationArbitration();
        arbitration.registerDomain("cloud-a1", "EU");
        arbitration.registerDomain("cloud-b1", "US");
        for (String cloud : Set.of("cloud-a1", "cloud-b1")) {
            arbitration.registerZone(cloud, "z1", true);
            arbitration.registerZone(cloud, "z2", true);
            arbitration.registerZone(cloud, "z3", true);
        }
        return arbitration;
    }

    private static void assertThatCoordinatorWorks(
            MultiAgentPushdownCoordinator coordinator) {
        if (coordinator.agentCount() != 1) {
            throw new AssertionError("agent count");
        }
    }

    private static void assertThatFederationWorks(
            MultiOrgFederationArbitration arbitration) {
        arbitration.registerOrganization("c1", "o1");
        arbitration.registerZone("c1", "z1", true);
        var result = arbitration.commit("smoke", Set.of("c1"));
        if (!result.succeeded()) {
            throw new AssertionError("federation failed");
        }
    }

    private static void assertThatArchiveWorks(
            io.tieringkv.ci.RunnerClosureArchive archive) {
        if (archive.size() != 1) {
            throw new AssertionError("archive size");
        }
    }
}
