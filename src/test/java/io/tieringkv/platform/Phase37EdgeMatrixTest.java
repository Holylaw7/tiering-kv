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
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
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
import io.tieringkv.security.network.NetworkPolicyDsl;
import io.tieringkv.security.network.NetworkPolicyDsl.PolicyRule;
import io.tieringkv.security.network.PolicyAuditView;
import io.tieringkv.security.network.PolicyCompiler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 37 参数化边缘矩阵：多目标/远端物化/证明/spot/审计/SLO。 */
class Phase37EdgeMatrixTest {

    @ParameterizedTest(name = "cost {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.7, 1.0})
    void moFenceCostFeedback(double cost) {
        MultiObjectiveFence fence = fence();
        double score = fence.score(new Feedback(cost, 0, 0));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "failure {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.7, 1.0})
    void moFenceFailureFeedback(double failure) {
        MultiObjectiveFence fence = fence();
        double score = fence.score(new Feedback(0, failure, 0));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "slo {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.7, 1.0})
    void moFenceSloFeedback(double slo) {
        MultiObjectiveFence fence = fence();
        double score = fence.score(new Feedback(0, 0, slo));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "weight {0}")
    @ValueSource(doubles = {0.0, 1.0, 5.0})
    void moFenceWeightDominance(double weight) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                new Weights(weight, 1, 1), 0.8, 0.2, 1, 1);
        double score = fence.score(new Feedback(1.0, 1.0, 0.0));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void moFenceRounds(int rounds) {
        MultiObjectiveFence fence = fence();
        for (int i = 0; i < rounds; i++) {
            fence.record(new Feedback(
                    i % 2 == 0 ? 1.0 : 0.0,
                    i % 3 == 0 ? 1.0 : 0.0,
                    i % 4 == 0 ? 1.0 : 0.0));
        }
        assertThat(fence.audit()).isNotNull();
    }

    @ParameterizedTest(name = "step {0}")
    @ValueSource(ints = {1, 2, 3, 5})
    void moFenceSteps(int step) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                new Weights(1, 1, 1), 0.8, 0.2, step, 1);
        fence.record(new Feedback(1.0, 0.0, 1.0));
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(10 + step);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(doubles = {0.6, 0.7, 0.8, 0.9})
    void moFenceRelaxThresholds(double threshold) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5), bounds(),
                new Weights(1, 1, 1), threshold, 0.2, 1, 1);
        var adjustment = fence.record(new Feedback(1.0, 0.0, 1.0));
        if (threshold <= 1.0) {
            assertThat(adjustment.reason()).isNotBlank();
        }
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void remoteMvAggregates(String aggregate) {
        RemoteMaterializationManager manager = remoteManager();
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.valueOf(aggregate)));
        manager.syncChange("v1",
                new CdcChange("a", ChangeType.INSERT, 4));
        manager.syncChange("v1",
                new CdcChange("b", ChangeType.INSERT, 8));
        assertThat(manager.snapshot("v1").count()).isPositive();
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 2, 4, 8})
    void remoteMvShardCounts(int count) {
        RemoteMaterializationManager manager = remoteManager();
        List<CloudShard> shards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            shards.add(new CloudShard("d" + i, "aws-us", "m"));
        }
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", shards, Aggregate.SUM));
        RemoteSnapshot snapshot = manager.refreshFull("v1",
                shard -> new io.tieringkv.datamesh
                        .CloudFederatedExecutor.CloudResult(
                        shard.domainId(), shard.cloud(), 2, 1));
        assertThat(snapshot.value()).isEqualTo(2L * count);
    }

    @ParameterizedTest(name = "changes {0}")
    @ValueSource(ints = {1, 10, 50, 100})
    void remoteMvChangeVolumes(int count) {
        RemoteMaterializationManager manager = remoteManager();
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        for (int i = 0; i < count; i++) {
            manager.syncChange("v1",
                    new CdcChange("k" + i, ChangeType.INSERT, 1));
        }
        assertThat(manager.snapshot("v1").value())
                .isEqualTo(count);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20, 50})
    void remoteMvInsertUpdateDelete(int rounds) {
        RemoteMaterializationManager manager = remoteManager();
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        for (int i = 0; i < rounds; i++) {
            manager.syncChange("v1",
                    new CdcChange("k", ChangeType.INSERT, i));
            manager.syncChange("v1",
                    new CdcChange("k", ChangeType.UPDATE, i + 1));
            manager.syncChange("v1",
                    new CdcChange("k", ChangeType.DELETE, 0));
        }
        assertThat(manager.snapshot("v1").value()).isZero();
    }

    @ParameterizedTest(name = "residency {0}")
    @ValueSource(strings = {"us", "eu", "cn", "default"})
    void remoteMvResidency(String residency) {
        RemoteMaterializationManager manager =
                new RemoteMaterializationManager(
                        new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-" + residency, residency,
                                "gcp-" + residency, residency)));
        manager.define(new RemoteDefinition("v1",
                "gcp-" + residency, "aws-" + residency,
                List.of(new CloudShard("d1", "aws-" + residency,
                        "m")), Aggregate.SUM));
        assertThat(manager.size()).isEqualTo(1);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void attestationExportLengths(int length) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < length; i++) {
            chain.append("GDPR", "v1", i % 4, i);
        }
        AttestationExporter exporter = new AttestationExporter();
        AttestationVerifier verifier = new AttestationVerifier();
        assertThat(verifier.verify(exporter.fromJson(
                exporter.toJson(chain)))).isTrue();
    }

    @ParameterizedTest(name = "violations {0}")
    @ValueSource(ints = {0, 1, 5, 100})
    void attestationExportViolations(int violations) {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", violations, 1000);
        String json = new AttestationExporter().toJson(chain);
        assertThat(json).contains("\"violations\":\""
                + violations + "\"");
    }

    @ParameterizedTest(name = "regulation {0}")
    @ValueSource(strings = {"GDPR", "SOC2", "PCI", "HIPAA"})
    void attestationExportRegulations(String regulation) {
        AttestationChain chain = new AttestationChain();
        chain.append(regulation, "v1", 0, 1000);
        List<AttestationChain.Attestation> parsed =
                new AttestationExporter().fromJson(
                        new AttestationExporter().toJson(chain));
        assertThat(parsed.get(0).regulation())
                .isEqualTo(regulation);
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.0, 0.2, 0.5, 0.9})
    void spotExpectedCostRates(double rate) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        assertThat(scheduler.expectedCost(new SpotOption(
                "aws-us", 10, rate, 100, true)))
                .isEqualTo(10 * (1 + rate * 2));
    }

    @ParameterizedTest(name = "penalty {0}")
    @ValueSource(doubles = {0.0, 1.0, 3.0, 10.0})
    void spotPenalties(double penalty) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler(
                penalty);
        assertThat(scheduler.expectedCost(new SpotOption(
                "aws-us", 10, 0.5, 100, true)))
                .isEqualTo(10 * (1 + 0.5 * penalty));
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(longs = {0, 50, 100, 200})
    void spotQuotaMatrix(long quota) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        var decision = scheduler.schedule(
                new SpotTask("t", "us", quota, false),
                List.of(new SpotOption("aws-us", 1, 0.0, 100,
                        true)),
                policy());
        assertThat(decision.isPresent()).isEqualTo(quota <= 100);
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void spotCandidateCounts(int count) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        List<SpotOption> options = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(new SpotOption("c" + i, i + 1, 0.0,
                    100, true));
        }
        var decision = scheduler.schedule(
                new SpotTask("t", "default", 10, false), options,
                new DataResidencyPolicy(Map.of()));
        assertThat(decision.orElseThrow().expectedCost())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "slo {0}")
    @ValueSource(booleans = {true, false})
    void spotSloRequirement(boolean slo) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        var decision = scheduler.schedule(
                new SpotTask("t", "us", 10, slo),
                List.of(
                        new SpotOption("aws-us", 1, 0.0, 100,
                                !slo),
                        new SpotOption("gcp-us", 2, 0.0, 100,
                                true)),
                policy());
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo(slo ? "gcp-us" : "aws-us");
    }

    @ParameterizedTest(name = "rules {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void policyAuditRuleCounts(int count) {
        IsolationPolicy policy = policy(100);
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dsl.append("allow: t").append(i % 90).append(" -> t")
                    .append((i + 1) % 90).append('\n');
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        new PolicyCompiler().apply(policy, dsl.toString(), audit);
        assertThat(audit.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "action {0}")
    @ValueSource(strings = {"allow", "deny", "allow", "deny"})
    void policyAuditActions(String action) {
        IsolationPolicy policy = policy(10);
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        new PolicyCompiler().apply(policy,
                action + ": t1 -> t2", audit);
        assertThat(audit.events().get(0).action())
                .isEqualTo(action);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20, 50})
    void policyAuditViewRounds(int rounds) {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        PolicyAuditView view = new PolicyAuditView();
        for (int i = 0; i < rounds; i++) {
            audit.record("src", new PolicyRule("allow",
                    "t" + i, "t" + (i + 1)), i);
        }
        assertThat(view.byTenant(audit).size())
                .isEqualTo(rounds + 1);
        assertThat(view.byAction(audit))
                .containsEntry("allow", (long) rounds);
    }

    @ParameterizedTest(name = "attainment {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.5, 0.8, 0.95, 1.0})
    void multiSloAttainments(double attainment) {
        NegotiationPlan plan = negotiator().negotiate(List.of(
                new SloInput("a", attainment, 0.9, 1)), 10, 50);
        if (attainment >= 0.9) {
            assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        } else {
            assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        }
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(doubles = {0.5, 0.8, 0.95, 0.99})
    void multiSloTargets(double target) {
        NegotiationPlan plan = negotiator().negotiate(List.of(
                new SloInput("a", target, target, 1)), 10, 50);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
    }

    @ParameterizedTest(name = "slo count {0}")
    @ValueSource(ints = {1, 3, 5, 10})
    void multiSloCounts(int count) {
        List<SloInput> inputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            inputs.add(new SloInput("s" + i,
                    i % 2 == 0 ? 0.7 : 0.95, 0.9, 1));
        }
        NegotiationPlan plan = negotiator().negotiate(inputs,
                10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @ParameterizedTest(name = "weight {0}")
    @ValueSource(doubles = {0.0, 1.0, 2.0, 10.0})
    void multiSloWeights(double weight) {
        NegotiationPlan plan = negotiator().negotiate(List.of(
                new SloInput("a", 0.8, 0.9, weight)), 10, 50);
        assertThat(plan.suggestedNodes()).isGreaterThanOrEqualTo(10);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 5, 10, 50})
    void multiSloNodes(int nodes) {
        NegotiationPlan plan = negotiator().negotiate(List.of(
                new SloInput("a", 0.5, 0.9, 1)),
                nodes, nodes * 3);
        assertThat(plan.suggestedNodes()).isGreaterThan(nodes);
    }

    @ParameterizedTest(name = "deficit {0}")
    @CsvSource({"0.85,0.9", "0.8,0.9", "0.7,0.9", "0.5,0.9"})
    void multiSloDeficitMatrix(double attainment, double target) {
        NegotiationPlan plan = negotiator().negotiate(List.of(
                new SloInput("a", attainment, target, 1)), 10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @ParameterizedTest(name = "factor {0}")
    @ValueSource(doubles = {1.0, 1.5, 2.0, 4.0})
    void multiSloFactors(double factor) {
        NegotiationPlan plan = new MultiSloNegotiator(factor)
                .negotiate(List.of(new SloInput("a", 0.5, 0.9,
                        1)), 10, 100);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {10, 11, 20, 100})
    void multiSloMaxCaps(int maxNodes) {
        NegotiationPlan plan = negotiator().negotiate(List.of(
                new SloInput("a", 0.0, 0.9, 1)), 10, maxNodes);
        assertThat(plan.suggestedNodes())
                .isLessThanOrEqualTo(maxNodes);
    }

    @ParameterizedTest(name = "min {0}")
    @ValueSource(ints = {1, 3, 5, 8, 10})
    void moFenceLowerBounds(int minActions) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(minActions, 5, 5),
                new Bounds(minActions, 20, 1, 10, 1, 8),
                new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
        for (int i = 0; i < 100; i++) {
            fence.record(new Feedback(0.0, 1.0, 0.0));
        }
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(minActions);
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {5, 10, 20, 50, 100})
    void moFenceUpperBounds(int maxActions) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5),
                new Bounds(1, maxActions, 1, 10, 1, 8),
                new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
        for (int i = 0; i < 100; i++) {
            fence.record(new Feedback(1.0, 0.0, 1.0));
        }
        assertThat(fence.params().maxActionsPerDay())
                .isLessThanOrEqualTo(maxActions);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50})
    void remoteMvViewCounts(int count) {
        RemoteMaterializationManager manager = remoteManager();
        for (int i = 0; i < count; i++) {
            manager.define(new RemoteDefinition("v" + i,
                    "gcp-us", "aws-us",
                    List.of(new CloudShard("d1", "aws-us", "m")),
                    Aggregate.SUM));
        }
        assertThat(manager.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "full rounds {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50})
    void remoteMvFullRefreshRounds(int rounds) {
        RemoteMaterializationManager manager = remoteManager();
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        for (int i = 0; i < rounds; i++) {
            manager.refreshFull("v1",
                    shard -> new io.tieringkv.datamesh
                            .CloudFederatedExecutor.CloudResult(
                            shard.domainId(), shard.cloud(), 5, 1));
        }
        assertThat(manager.snapshot("v1").value()).isEqualTo(5);
    }

    @ParameterizedTest(name = "regulations {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void attestationMixedRegulations(int count) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < count; i++) {
            chain.append("R" + i, "v1", 0, i);
        }
        String json = new AttestationExporter().toJson(chain);
        assertThat(new AttestationVerifier().verify(
                new AttestationExporter().fromJson(json))).isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 25, 50, 100})
    void attestationExportRounds(int rounds) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < rounds; i++) {
            chain.append("GDPR", "v1", i % 3, i);
        }
        assertThat(new AttestationVerifier().verify(
                new AttestationExporter().fromJson(
                        new AttestationExporter().toJson(chain))))
                .isTrue();
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.1, 1.0, 5.0, 10.0, 100.0})
    void spotPriceMatrix(double price) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        var decision = scheduler.schedule(
                new SpotTask("t", "us", 10, false),
                List.of(
                        new SpotOption("aws-us", price, 0.0, 100,
                                true),
                        new SpotOption("gcp-us", price * 2, 0.0,
                                100, true)),
                policy());
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
    }

    @ParameterizedTest(name = "residency {0}")
    @ValueSource(strings = {"us", "eu", "cn", "default", "other"})
    void spotResidencyMatrix(String residency) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        var decision = scheduler.schedule(
                new SpotTask("t", residency, 10, false),
                List.of(new SpotOption("aws-" + residency, 1,
                        0.0, 100, true)),
                new DataResidencyPolicy(Map.of(
                        "aws-" + residency, residency)));
        assertThat(decision).isPresent();
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {5, 10, 20, 50, 100})
    void policyAuditTenantCounts(int count) {
        IsolationPolicy policy = policy(count);
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < count - 1; i++) {
            dsl.append("allow: t").append(i).append(" -> t")
                    .append(i + 1).append('\n');
        }
        new PolicyCompiler().apply(policy, dsl.toString(), audit);
        assertThat(audit.size()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void policyAuditViewVolumes(int count) {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        for (int i = 0; i < count; i++) {
            audit.record("src", new PolicyRule(
                    i % 2 == 0 ? "allow" : "deny",
                    "t" + (i % 20), "t" + ((i + 1) % 20)), i);
        }
        assertThat(new PolicyAuditView().byAction(audit)
                .values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(count);
    }

    @ParameterizedTest(name = "attainment {0}")
    @ValueSource(doubles = {0.0, 0.2, 0.5, 0.8, 1.0})
    void multiSloWeightedDeficit(double attainment) {
        NegotiationPlan plan = negotiator().negotiate(List.of(
                new SloInput("a", attainment, 0.9, 1),
                new SloInput("b", 0.95, 0.9, 1)), 10, 50);
        assertThat(plan.worstDeficit()).isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "slo count {0}")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    void multiSloLargeCounts(int count) {
        List<SloInput> inputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            inputs.add(new SloInput("s" + i,
                    i % 3 == 0 ? 0.6 : 0.95, 0.9, 1));
        }
        NegotiationPlan plan = negotiator().negotiate(inputs,
                10, 50);
        assertThat(plan.suggestedNodes()).isBetween(10, 50);
    }

    private static MultiObjectiveFence fence() {
        return new MultiObjectiveFence(new Params(10, 5, 5),
                bounds(), new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
    }

    private static Bounds bounds() {
        return new Bounds(1, 20, 1, 10, 1, 8);
    }

    private static RemoteMaterializationManager remoteManager() {
        return new RemoteMaterializationManager(
                new ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us",
                        "aws-eu", "eu")));
    }

    private static DataResidencyPolicy policy() {
        return new DataResidencyPolicy(Map.of(
                "aws-us", "us", "gcp-us", "us"));
    }

    private static IsolationPolicy policy(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        return policy;
    }

    private static MultiSloNegotiator negotiator() {
        return new MultiSloNegotiator();
    }
}
