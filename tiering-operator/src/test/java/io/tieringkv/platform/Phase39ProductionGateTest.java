package io.tieringkv.platform;

import io.tieringkv.capacity.ai.MultiAgentAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.ChainAnchor;
import io.tieringkv.compliance.ChainVerifier;
import io.tieringkv.datamesh.AutoTierManager;
import io.tieringkv.datamesh.AutoTierManager.Tier;
import io.tieringkv.observability.cost.SpotMarketFeed;
import io.tieringkv.observability.cost.SpotRatePredictor;
import io.tieringkv.operations.slo.ParetoCapacityOptimizer;
import io.tieringkv.operations.slo.ParetoCapacityOptimizer.Candidate;
import io.tieringkv.security.network.AdaptiveHardener;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.NetworkPolicyAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 39 生产门禁（JVM 级）：多智能体/分层/锚定/预测/加固/Pareto。 */
class Phase39ProductionGateTest {

    @Test
    void multiAgentFederatedGate() {
        MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
        autonomy.registerRegion("r1", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r2", 0.5, 0.0, 10.0);
        for (int i = 0; i < 20; i++) {
            autonomy.record("r1", Action.RELAX, 1.0);
            autonomy.record("r2", Action.RELAX, 1.0);
        }
        var weights = autonomy.aggregate();
        assertThat(weights.get(Action.RELAX))
                .isGreaterThan(weights.get(Action.TIGHTEN));
        assertThat(autonomy.audit()).isNotEmpty();
    }

    @Test
    void autoTierHeatGate() {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < 150; i++) {
            manager.recordAccess("hot");
        }
        for (int i = 0; i < 50; i++) {
            manager.recordAccess("warm");
        }
        assertThat(manager.decide("hot", 100, 10))
                .isEqualTo(Tier.HOT);
        assertThat(manager.decide("warm", 100, 10))
                .isEqualTo(Tier.WARM);
        assertThat(manager.decide("cold", 100, 10))
                .isEqualTo(Tier.COLD);
    }

    @Test
    void chainAnchorGate() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        var head = chain.attestations().get(0).hash();
        var record = ChainAnchor.anchor("chain-1", "block-42",
                2000, head);
        assertThat(new ChainVerifier().verify(record, head))
                .isTrue();
    }

    @Test
    void chainAnchorTamperGate() {
        var record = ChainAnchor.anchor("chain-1", "block-1",
                1000, "head");
        var tampered = new ChainAnchor.AnchorRecord(
                record.chainId(), "block-2", record.timestampMillis(),
                record.headHash(), record.anchorHash());
        assertThat(new ChainVerifier().verify(tampered)).isFalse();
    }

    @Test
    void spotMarketPredictionGate() {
        SpotMarketFeed feed = new SpotMarketFeed();
        SpotRatePredictor predictor = new SpotRatePredictor();
        List<Double> rates = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double rate = 0.1 + i * 0.01;
            feed.publish("aws-us", i, 1.0, rate);
            rates.add(rate);
        }
        double predicted = predictor.movingAverage(rates, 5);
        assertThat(predicted).isBetween(0.1, 0.5);
        assertThat(feed.latest("aws-us").interruptionRate())
                .isGreaterThan(0.1);
    }

    @Test
    void adaptiveHardeningGate() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc-2", "subnet-2", true));
        policy.allow("t1", "t2");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        AdaptiveHardener hardener = new AdaptiveHardener();
        assertThat(hardener.harden(policy, 30, audit))
                .isEqualTo(1);
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
        assertThat(hardener.rollback(policy, audit))
                .isEqualTo(1);
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
    }

    @Test
    void paretoGate() {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        var front = optimizer.paretoFront(List.of(
                new Candidate("slo", 20, 0.9, 0.5, 0.1),
                new Candidate("cost", 5, 0.5, 0.1, 0.9)));
        assertThat(front).hasSize(2);
        assertThat(optimizer.chooseByWeights(front, 1, 0, 0)
                .name()).isEqualTo("slo");
        assertThat(optimizer.chooseByWeights(front, 0, 1, 0)
                .name()).isEqualTo("cost");
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedAgentCounts(int count) {
        MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
        for (int i = 0; i < count; i++) {
            autonomy.registerRegion("r" + i, 0.1, 0.0, 10.0);
        }
        var weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> w > 0);
    }

    @ParameterizedTest(name = "access {0}")
    @ValueSource(ints = {0, 10, 100})
    void parameterizedTierLevels(int access) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < access; i++) {
            manager.recordAccess("v1");
        }
        Tier tier = manager.decide("v1", 100, 10);
        if (access >= 100) {
            assertThat(tier).isEqualTo(Tier.HOT);
        } else if (access >= 10) {
            assertThat(tier).isEqualTo(Tier.WARM);
        } else {
            assertThat(tier).isEqualTo(Tier.COLD);
        }
    }

    @ParameterizedTest(name = "window {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedPredictionWindows(int window) {
        SpotRatePredictor predictor = new SpotRatePredictor();
        List<Double> rates = List.of(0.1, 0.2, 0.3, 0.4, 0.5);
        assertThat(predictor.movingAverage(rates, window))
                .isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedHardeningPairs(int pairs) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i <= pairs; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(new AdaptiveHardener().harden(policy, 0, audit))
                .isEqualTo(pairs);
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedParetoCandidates(int count) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        List<Candidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(new Candidate("c" + i, 1 + i,
                    0.9 - i * 0.02, 0.1 + i * 0.02,
                    0.1 + i * 0.02));
        }
        assertThat(optimizer.paretoFront(candidates)).isNotEmpty();
    }

    @Test
    void multiAgentMajorityGate() {
        MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
        autonomy.registerRegion("r1", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r2", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r3", 0.5, 0.0, 10.0);
        for (int i = 0; i < 20; i++) {
            autonomy.record("r1", Action.RELAX, 1.0);
            autonomy.record("r2", Action.RELAX, 1.0);
            autonomy.record("r3", Action.TIGHTEN, 1.0);
        }
        var weights = autonomy.aggregate();
        assertThat(weights.get(Action.RELAX))
                .isGreaterThan(weights.get(Action.TIGHTEN));
    }
}
