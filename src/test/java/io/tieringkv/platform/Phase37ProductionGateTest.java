package io.tieringkv.platform;

import io.tieringkv.capacity.ai.MultiObjectiveFence;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Bounds;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Feedback;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Params;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Weights;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.AttestationExporter;
import io.tieringkv.compliance.AttestationVerifier;
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.CdcChange;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.ChangeType;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.RemoteMaterializationManager;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteDefinition;
import io.tieringkv.observability.cost.SpotAwareScheduler;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;
import io.tieringkv.operations.slo.MultiSloNegotiator;
import io.tieringkv.operations.slo.MultiSloNegotiator.Action;
import io.tieringkv.operations.slo.MultiSloNegotiator.NegotiationPlan;
import io.tieringkv.operations.slo.MultiSloNegotiator.SloInput;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.NetworkPolicyAudit;
import io.tieringkv.security.network.PolicyAuditView;
import io.tieringkv.security.network.PolicyCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 37 生产门禁（JVM 级）：多目标/远端物化/证明/spot/审计/SLO。 */
class Phase37ProductionGateTest {

    @Test
    void multiObjectiveFenceGate() {
        MultiObjectiveFence fence = fence();
        var relaxed = fence.record(new Feedback(1.0, 0.0, 1.0));
        assertThat(relaxed.reason()).isEqualTo("relax");
        var tightened = fence.record(new Feedback(0.0, 1.0, 0.0));
        assertThat(tightened.reason()).isEqualTo("tighten");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(10);
    }

    @Test
    void multiObjectiveFenceCircuitGate() {
        MultiObjectiveFence fence = fence();
        fence.recordRollback("migration failed");
        assertThat(fence.circuitOpen()).isTrue();
        fence.resetCircuit();
        assertThat(fence.circuitOpen()).isFalse();
    }

    @Test
    void remoteMaterializationSyncGate() {
        RemoteMaterializationManager manager = remoteManager();
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.INSERT, 10));
        manager.syncChange("v1",
                new CdcChange("k2", ChangeType.INSERT, 5));
        assertThat(manager.snapshot("v1").value()).isEqualTo(15);
        assertThat(manager.isStale("v1")).isFalse();
    }

    @Test
    void remoteMaterializationSovereigntyGate() {
        RemoteMaterializationManager manager = remoteManager();
        assertThatThrownBy(() -> manager.define(
                new RemoteDefinition("v1", "aws-eu", "aws-us",
                        List.of(new CloudShard("d1", "aws-us",
                                "m")), Aggregate.SUM)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void thirdPartyAttestationGate() {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < 10; i++) {
            chain.append("GDPR", "v1", i % 3, i);
        }
        AttestationExporter exporter = new AttestationExporter();
        AttestationVerifier verifier = new AttestationVerifier();
        String json = exporter.toJson(chain);
        assertThat(verifier.verify(exporter.fromJson(json)))
                .isTrue();
    }

    @Test
    void thirdPartyAttestationTamperGate() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        var json = new AttestationExporter().toJson(chain)
                .replace("\"violations\":\"0\"",
                        "\"violations\":\"99\"");
        assertThat(new AttestationVerifier().verify(
                new AttestationExporter().fromJson(json)))
                .isFalse();
    }

    @Test
    void spotSchedulerGate() {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        var decision = scheduler.schedule(
                new SpotTask("t1", "us", 10, false),
                List.of(
                        new SpotOption("aws-us", 2, 0.8, 100, true),
                        new SpotOption("gcp-us", 4, 0.0, 100, true)),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")));
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void spotSchedulerSovereigntyGate() {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        assertThat(scheduler.schedule(
                new SpotTask("t1", "us", 10, false),
                List.of(new SpotOption("aws-eu", 1, 0.0, 100,
                        true)),
                new DataResidencyPolicy(Map.of(
                        "aws-eu", "eu")))).isEmpty();
    }

    @Test
    void policyAuditCompileGate() {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 1; i <= 3; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        new PolicyCompiler().apply(policy,
                "allow: t1 -> t2\ndeny: t1 -> t3", audit);
        assertThat(audit.size()).isEqualTo(2);
        assertThat(new PolicyAuditView().byAction(audit))
                .containsEntry("allow", 1L)
                .containsEntry("deny", 1L);
    }

    @Test
    void multiSloNegotiationGate() {
        NegotiationPlan plan = new MultiSloNegotiator()
                .negotiate(List.of(
                        new SloInput("latency", 0.8, 0.9, 1),
                        new SloInput("availability", 0.95, 0.9, 1)),
                        10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        assertThat(plan.worstSloId()).isEqualTo("latency");
    }

    @Test
    void multiSloCompliantGate() {
        NegotiationPlan plan = new MultiSloNegotiator()
                .negotiate(List.of(
                        new SloInput("a", 0.95, 0.9, 1),
                        new SloInput("b", 0.91, 0.9, 1)),
                        10, 50);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
    }

    @ParameterizedTest(name = "tenant {0}")
    @ValueSource(ints = {5, 20})
    void parameterizedPolicyAuditCounts(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < count - 1; i++) {
            dsl.append("allow: t").append(i).append(" -> t")
                    .append(i + 1).append('\n');
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        new PolicyCompiler().apply(policy, dsl.toString(), audit);
        assertThat(audit.size()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "attainment {0}")
    @ValueSource(doubles = {0.0, 0.5, 0.9, 1.0})
    void parameterizedMultiSlo(double attainment) {
        NegotiationPlan plan = new MultiSloNegotiator()
                .negotiate(List.of(
                        new SloInput("a", attainment, 0.9, 1)),
                        10, 50);
        assertThat(plan.suggestedNodes()).isBetween(10, 50);
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.0, 0.5, 0.9})
    void parameterizedSpotRates(double rate) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        double expected = 10 * (1 + rate * 2);
        assertThat(scheduler.expectedCost(new SpotOption(
                "aws-us", 10, rate, 100, true)))
                .isEqualTo(expected);
    }

    private static MultiObjectiveFence fence() {
        return new MultiObjectiveFence(new Params(10, 5, 5),
                new Bounds(1, 20, 1, 10, 1, 8),
                new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
    }

    private static RemoteMaterializationManager remoteManager() {
        return new RemoteMaterializationManager(
                new ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us",
                        "aws-eu", "eu")));
    }
}
