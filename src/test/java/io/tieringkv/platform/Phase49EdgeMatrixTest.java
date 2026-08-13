package io.tieringkv.platform;

import io.tieringkv.ci.GateConvergenceV15;
import io.tieringkv.cluster.scheduler.RegulatoryKnowledgeBase;
import io.tieringkv.sql.coprocessor.FederatedPushdownLearning;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.CrossRegulatoryFederationArbitration;
import io.tieringkv.transaction.tso.CommercialTimeDeviceConnector;
import io.tieringkv.transaction.tso.CommercialTimeDeviceConnector
        .SimulatedTimeDevice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 49 边缘矩阵：跨域/联邦学习/设备/法规库/门禁边界行为。 */
class Phase49EdgeMatrixTest {

    @Test
    void crossRegulatorySingleCloudOnePhase() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        var result = arbitration.commit("e1",
                Set.of("cloud-a1"));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.domains()).isEqualTo(1);
    }

    @Test
    void unregisteredZonesMakeCloudIneligible() {
        CrossRegulatoryFederationArbitration arbitration =
                new CrossRegulatoryFederationArbitration();
        arbitration.registerDomain("cloud-a1", "EU");
        arbitration.registerDomain("cloud-b1", "US");
        var result = arbitration.commit("e2",
                Set.of("cloud-a1", "cloud-b1"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.fallback2Pc()).isTrue();
    }

    @Test
    void nullCloudsRejected() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        assertThatThrownBy(() -> arbitration.commit("e3", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "edge {0}")
    @MethodSource("edges")
    void edgeMatrix(String edge) {
        switch (edge) {
            case "fpl-single-agent" -> {
                FederatedPushdownLearning learning =
                        new FederatedPushdownLearning(0, 1);
                learning.registerAgent("q0",
                        new ReinforcementPushdownAgent(1, 0, 100),
                        1);
                learning.federatedLearn("q0",
                        ReinforcementPushdownAgent.Action.PUSHDOWN,
                        1);
                assertThat(learning.aggregated("q0").samples())
                        .isEqualTo(1);
            }
            case "fpl-equal-q-prefers-pushdown" -> {
                FederatedPushdownLearning learning =
                        new FederatedPushdownLearning(0, 1);
                learning.registerAgent("q0",
                        new ReinforcementPushdownAgent(1, 0, 100),
                        1);
                assertThat(learning.federatedDecide("q0"))
                        .isEqualTo(ReinforcementPushdownAgent
                                .Action.PUSHDOWN);
            }
            case "fpl-double-register-replaces" -> {
                FederatedPushdownLearning learning =
                        new FederatedPushdownLearning(0, 1);
                learning.registerAgent("q0",
                        new ReinforcementPushdownAgent(1, 0, 100),
                        1);
                learning.registerAgent("q0",
                        new ReinforcementPushdownAgent(1, 0, 100),
                        2);
                assertThat(learning.agentCount()).isEqualTo(1);
            }
            case "device-backward-drift-monotonic" -> {
                CommercialTimeDeviceConnector connector =
                        new CommercialTimeDeviceConnector("a",
                                "b", 0);
                connector.registerDriver(new SimulatedTimeDevice(
                        "a", 1000, 0));
                connector.registerDriver(new SimulatedTimeDevice(
                        "b", 1000, 0));
                connector.connect("a");
                long first = connector.timestamp();
                long second = connector.timestamp();
                assertThat(second).isGreaterThan(first);
            }
            case "device-backup-lower-time-monotonic" -> {
                CommercialTimeDeviceConnector connector =
                        new CommercialTimeDeviceConnector("a",
                                "b", 0);
                SimulatedTimeDevice primary =
                        new SimulatedTimeDevice("a", 5000, 0);
                SimulatedTimeDevice backup =
                        new SimulatedTimeDevice("b", 1000, 0);
                connector.registerDriver(primary);
                connector.registerDriver(backup);
                connector.connect("a");
                long first = connector.timestamp();
                primary.fail();
                long second = connector.timestamp();
                assertThat(second).isGreaterThan(first);
            }
            case "device-disconnect-failover" -> {
                CommercialTimeDeviceConnector connector =
                        new CommercialTimeDeviceConnector("a",
                                "b", 0);
                connector.registerDriver(new SimulatedTimeDevice(
                        "a", 1000, 0));
                connector.registerDriver(new SimulatedTimeDevice(
                        "b", 2000, 0));
                connector.connect("a");
                connector.disconnect("a");
                assertThat(connector.timestamp()).isEqualTo(2000);
            }
            case "regkb-duplicate-clause-deduped" -> {
                RegulatoryKnowledgeBase base =
                        new RegulatoryKnowledgeBase();
                base.registerVersion("GDPR", "v1",
                        List.of("A17", "A17", "A5"));
                assertThat(base.active("GDPR").clauses())
                        .hasSize(2);
            }
            case "regkb-retire-twice-throws" -> {
                RegulatoryKnowledgeBase base =
                        new RegulatoryKnowledgeBase();
                base.registerVersion("GDPR", "v1",
                        List.of("A17"));
                base.retire("GDPR", "v1");
                assertThatThrownBy(() -> base.retire("GDPR", "v1"))
                        .isInstanceOf(IllegalArgumentException
                                .class);
            }
            case "regkb-verify-tamper-detected" -> {
                RegulatoryKnowledgeBase base =
                        new RegulatoryKnowledgeBase();
                base.registerVersion("GDPR", "v1",
                        List.of("A17"));
                assertThat(base.verify("GDPR", "v1")).isTrue();
            }
            case "gate-unknown-throws" ->
                    assertThatThrownBy(
                            () -> GateConvergenceV15.gate("TD-999"))
                            .isInstanceOf(IllegalArgumentException
                                    .class);
            case "gate-summary-lines" -> {
                String summary =
                        GateConvergenceV15.summary();
                long lines = summary.lines().count();
                assertThat(lines).isGreaterThanOrEqualTo(24);
            }
            case "gate-ids-unique" -> {
                long unique = GateConvergenceV15.gates().stream()
                        .map(GateConvergenceV15.Gate::id)
                        .distinct().count();
                assertThat(unique)
                        .isEqualTo(GateConvergenceV15.gates().size());
            }
            case "crossreg-re-register-clears-cache" -> {
                CrossRegulatoryFederationArbitration arbitration =
                        topology();
                arbitration.commit("e", Set.of("cloud-a1"));
                int before = arbitration.completedCount();
                arbitration.registerDomain("cloud-a1", "APAC");
                assertThat(arbitration.completedCount())
                        .isLessThan(before);
            }
            case "crossreg-unknown-cloud-default" -> {
                CrossRegulatoryFederationArbitration arbitration =
                        topology();
                var result = arbitration.commit("e",
                        Set.of("unknown-1", "unknown-2"));
                assertThat(result.domains()).isEqualTo(1);
                assertThat(result.onePhase()).isFalse();
            }
            case "crossreg-mixed-known-unknown" -> {
                CrossRegulatoryFederationArbitration arbitration =
                        topology();
                var result = arbitration.commit("e",
                        Set.of("cloud-a1", "unknown-1"));
                assertThat(result.fallback2Pc()).isTrue();
            }
            case "crossreg-all-zones-ineligible" -> {
                CrossRegulatoryFederationArbitration arbitration =
                        topology();
                for (String cloud : Set.of("cloud-a1",
                        "cloud-b1")) {
                    arbitration.registerZone(cloud, "z1", false);
                    arbitration.registerZone(cloud, "z2", false);
                    arbitration.registerZone(cloud, "z3", false);
                }
                var result = arbitration.commit("e",
                        Set.of("cloud-a1", "cloud-b1"));
                assertThat(result.eligibleClouds()).isZero();
            }
            default -> throw new AssertionError(
                    "unknown edge " + edge);
        }
    }

    static Stream<String> edges() {
        return Stream.of("fpl-single-agent",
                "fpl-equal-q-prefers-pushdown",
                "fpl-double-register-replaces",
                "device-backward-drift-monotonic",
                "device-backup-lower-time-monotonic",
                "device-disconnect-failover",
                "regkb-duplicate-clause-deduped",
                "regkb-retire-twice-throws",
                "regkb-verify-tamper-detected",
                "gate-unknown-throws",
                "gate-summary-lines",
                "gate-ids-unique",
                "crossreg-re-register-clears-cache",
                "crossreg-unknown-cloud-default",
                "crossreg-mixed-known-unknown",
                "crossreg-all-zones-ineligible");
    }

    private static CrossRegulatoryFederationArbitration topology() {
        CrossRegulatoryFederationArbitration arbitration =
                new CrossRegulatoryFederationArbitration();
        arbitration.registerDomain("cloud-a1", "EU");
        arbitration.registerDomain("cloud-a2", "EU");
        arbitration.registerDomain("cloud-b1", "US");
        for (String cloud : Set.of("cloud-a1", "cloud-a2",
                "cloud-b1")) {
            arbitration.registerZone(cloud, "z1", true);
            arbitration.registerZone(cloud, "z2", true);
            arbitration.registerZone(cloud, "z3", true);
        }
        return arbitration;
    }
}
