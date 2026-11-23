package io.tieringkv.platform;

import io.tieringkv.capacity.ai.MultiAgentAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.ChainAnchor;
import io.tieringkv.compliance.ChainAnchor.AnchorRecord;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 39 参数化边缘矩阵：多智能体/分层/锚定/预测/加固/Pareto。 */
class Phase39EdgeMatrixTest {

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20})
    void multiAgentCounts(int count) {
        MultiAgentAutonomy autonomy = autonomy(count);
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> w > 0);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void multiAgentRounds(int rounds) {
        MultiAgentAutonomy autonomy = autonomy(2);
        for (int i = 0; i < rounds; i++) {
            autonomy.record("r" + (i % 2), Action.RELAX, 1.0);
        }
        assertThat(autonomy.aggregate().get(Action.RELAX))
                .isGreaterThan(autonomy.aggregate()
                        .get(Action.TIGHTEN));
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.1, 0.3, 0.5, 0.8, 1.0})
    void multiAgentRates(double rate) {
        MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
        autonomy.registerRegion("r0", rate, 0.0, 10.0);
        autonomy.record("r0", Action.RELAX, 1.0);
        assertThat(autonomy.q("r0", Action.RELAX)).isEqualTo(rate);
    }

    @ParameterizedTest(name = "access {0}")
    @ValueSource(ints = {0, 5, 10, 50, 100})
    void autoTierAccessLevels(int access) {
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

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void autoTierThresholds(int threshold) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < threshold; i++) {
            manager.recordAccess("v1");
        }
        assertThat(manager.decide("v1", threshold, 1))
                .isEqualTo(Tier.HOT);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 20, 50, 100})
    void autoTierViews(int count) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < count; i++) {
            manager.recordAccess("v" + i);
            manager.decide("v" + i, 100, 10);
        }
        assertThat(manager.viewIds()).hasSize(count);
    }

    @ParameterizedTest(name = "chain {0}")
    @ValueSource(strings = {"ethereum", "solana", "polygon",
            "local-chain", "anchor"})
    void chainAnchorChains(String chainId) {
        AnchorRecord record = ChainAnchor.anchor(chainId,
                "block-1", 1000, "head");
        assertThat(new ChainVerifier().verify(record)).isTrue();
    }

    @ParameterizedTest(name = "block {0}")
    @ValueSource(strings = {"genesis", "block-0", "block-42",
            "block-999", "latest"})
    void chainAnchorBlocks(String blockId) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                blockId, 1000, "head");
        assertThat(new ChainVerifier().verify(record)).isTrue();
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {0, 100, 1_000_000, 1_700_000_000_000L})
    void chainAnchorTimes(long timestamp) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-1", timestamp, "head");
        assertThat(new ChainVerifier().verify(record)).isTrue();
        assertThat(record.timestampMillis()).isEqualTo(timestamp);
    }

    @ParameterizedTest(name = "head {0}")
    @ValueSource(strings = {"a", "abc", "hash1", "sha-256"})
    void chainAnchorHeads(String head) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-1", 1000, head);
        assertThat(new ChainVerifier().verify(record, head))
                .isTrue();
    }

    @ParameterizedTest(name = "window {0}")
    @ValueSource(ints = {1, 2, 5, 10, 50})
    void spotPredictWindows(int window) {
        SpotRatePredictor predictor = new SpotRatePredictor();
        List<Double> rates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rates.add(0.1 + i * 0.01);
        }
        assertThat(predictor.movingAverage(rates, window))
                .isBetween(0.1, 0.5);
    }

    @ParameterizedTest(name = "alpha {0}")
    @ValueSource(doubles = {0.1, 0.3, 0.5, 0.8, 1.0})
    void spotPredictAlphas(double alpha) {
        SpotRatePredictor predictor = new SpotRatePredictor();
        assertThat(predictor.exponentialSmoothing(
                List.of(0.1, 0.3, 0.5, 0.7), alpha))
                .isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void spotFeedTicks(int count) {
        SpotMarketFeed feed = new SpotMarketFeed();
        for (int i = 0; i < count; i++) {
            feed.publish("aws-us", i, 1.0, 0.1 + (i % 5) * 0.1);
        }
        assertThat(feed.tickCount("aws-us")).isEqualTo(count);
        assertThat(feed.history("aws-us")).hasSize(count);
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"aws-us", "gcp-us", "azure-us",
            "aws-eu", "gcp-eu"})
    void spotFeedClouds(String cloud) {
        SpotMarketFeed feed = new SpotMarketFeed();
        feed.publish(cloud, 1, 1.0, 0.1);
        assertThat(feed.latest(cloud).cloud()).isEqualTo(cloud);
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.0, 0.5, 1.0, 5.0, 100.0})
    void spotFeedPrices(double price) {
        SpotMarketFeed feed = new SpotMarketFeed();
        feed.publish("aws-us", 1, price, 0.1);
        assertThat(feed.latest("aws-us").price()).isEqualTo(price);
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 3, 5, 10, 20})
    void hardenPairs(int pairs) {
        IsolationPolicy policy = riskPolicy(pairs + 2, true);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(new AdaptiveHardener().harden(policy, 30, audit))
                .isEqualTo(pairs);
        assertThat(policy.whitelistEntries()).isEmpty();
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {0, 10, 30, 50, 100})
    void hardenThresholds(int threshold) {
        IsolationPolicy policy = riskPolicy(4, true);
        policy.allow("t0", "t1");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        int revoked = new AdaptiveHardener().harden(policy,
                threshold, audit);
        assertThat(revoked).isEqualTo(threshold <= 30 ? 1 : 0);
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 3, 5, 10, 20})
    void paretoCandidates(int count) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(new Candidate("c" + i, 1 + i,
                    0.9 - i * 0.02, 0.1 + i * 0.02,
                    0.1 + i * 0.02));
        }
        assertThat(optimizer.paretoFront(candidates)).isNotEmpty();
    }

    @ParameterizedTest(name = "weight {0}")
    @ValueSource(doubles = {0.0, 0.25, 0.5, 0.75, 1.0})
    void paretoWeights(double weight) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        var front = optimizer.paretoFront(List.of(
                new Candidate("a", 10, 0.9, 0.5, 0.1),
                new Candidate("b", 5, 0.5, 0.1, 0.9)));
        assertThat(optimizer.chooseByWeights(front,
                weight, weight, weight)).isNotNull();
    }

    @ParameterizedTest(name = "slo {0}")
    @ValueSource(doubles = {0.1, 0.3, 0.5, 0.7, 0.9})
    void paretoSloScores(double slo) {
        Candidate a = new Candidate("a", 10, slo, 0.5, 0.1);
        Candidate b = new Candidate("b", 5, 0.5, 0.1, 0.9);
        assertThat(new ParetoCapacityOptimizer().dominates(a, b))
                .isFalse();
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 5, 10, 50, 100})
    void paretoNodes(int nodes) {
        Candidate candidate = new Candidate("c", nodes,
                0.5, 0.5, 0.5);
        assertThat(candidate.nodes()).isEqualTo(nodes);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"10,2", "20,5", "50,10", "100,20", "200,50"})
    void hardenMix(int tenants, int pairs) {
        IsolationPolicy policy = riskPolicy(tenants, true);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(new AdaptiveHardener().harden(policy, 30, audit))
                .isEqualTo(pairs);
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    void multiAgentLargeCounts(int count) {
        MultiAgentAutonomy autonomy = autonomy(count);
        Map<Action, Double> weights = autonomy.aggregate();
        double sum = weights.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @ParameterizedTest(name = "reward {0}")
    @ValueSource(doubles = {-10.0, -1.0, 0.0, 1.0, 10.0})
    void multiAgentRewards(double reward) {
        MultiAgentAutonomy autonomy = autonomy(1);
        autonomy.record("r0", Action.RELAX, reward);
        assertThat(autonomy.q("r0", Action.RELAX))
                .isEqualTo(reward * 0.5);
    }

    @ParameterizedTest(name = "access {0}")
    @ValueSource(ints = {1, 10, 100, 500, 1000})
    void autoTierLargeAccess(int access) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < access; i++) {
            manager.recordAccess("v1");
        }
        assertThat(manager.accessCount("v1")).isEqualTo(access);
    }

    @ParameterizedTest(name = "block {0}")
    @ValueSource(longs = {0, 7, 42, 1000, 999999})
    void chainAnchorBlockIds(long blockId) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-" + blockId, 1000, "head");
        assertThat(new ChainVerifier().verify(record)).isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void chainAnchorRounds(int rounds) {
        ChainVerifier verifier = new ChainVerifier();
        for (int i = 0; i < rounds; i++) {
            AnchorRecord record = ChainAnchor.anchor("chain-1",
                    "block-" + i, i, "head");
            assertThat(verifier.verify(record)).isTrue();
        }
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.01, 0.1, 0.3, 0.5, 0.9})
    void spotPredictRates(double rate) {
        SpotRatePredictor predictor = new SpotRatePredictor();
        assertThat(predictor.exponentialSmoothing(
                List.of(rate, rate, rate), 0.5))
                .isCloseTo(rate,
                        org.assertj.core.data.Offset.offset(1e-9));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void hardenRollbackRounds(int rounds) {
        IsolationPolicy policy = riskPolicy(10, true);
        for (int i = 0; i < 5; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        AdaptiveHardener hardener = new AdaptiveHardener();
        for (int i = 0; i < rounds; i++) {
            hardener.harden(policy, 0, audit);
            hardener.rollback(policy, audit);
        }
        assertThat(policy.canCommunicate("t0", "t1")).isTrue();
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"10,0.8,0.2", "20,0.6,0.4", "50,0.9,0.1",
            "100,0.7,0.3", "200,0.5,0.5"})
    void paretoMix(int nodes, double slo, double cost) {
        Candidate a = new Candidate("a", nodes, slo, cost, 0.1);
        Candidate b = new Candidate("b", nodes / 2, slo - 0.2,
                cost * 0.5, 0.9);
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        assertThat(optimizer.paretoFront(List.of(a, b)))
                .isNotEmpty();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void multiAgentAggregateRounds(int rounds) {
        MultiAgentAutonomy autonomy = autonomy(3);
        for (int i = 0; i < rounds; i++) {
            autonomy.record("r" + (i % 3), Action.RELAX, 1.0);
            if (i % 10 == 0) {
                autonomy.aggregate();
            }
        }
        assertThat(autonomy.audit()).isNotEmpty();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 100})
    void autoTierAccessVolumes(int count) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < count; i++) {
            manager.recordAccess("v" + (i % 5));
        }
        assertThat(manager.viewIds()).hasSize(Math.min(5, count));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void autoTierDecideRounds(int rounds) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < rounds; i++) {
            manager.recordAccess("v1");
            manager.decide("v1", 1000, 100);
        }
        assertThat(manager.tier("v1")).isEqualTo(
                rounds >= 100 ? Tier.WARM : Tier.COLD);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void chainAnchorVolumes(int count) {
        ChainVerifier verifier = new ChainVerifier();
        for (int i = 0; i < count; i++) {
            AnchorRecord record = ChainAnchor.anchor("chain-1",
                    "block-" + i, i, "head-" + i);
            assertThat(verifier.verify(record)).isTrue();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void chainAnchorVerifyRounds(int rounds) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-1", 1, "head");
        ChainVerifier verifier = new ChainVerifier();
        for (int i = 0; i < rounds; i++) {
            assertThat(verifier.verify(record)).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void spotFeedVolumes(int count) {
        SpotMarketFeed feed = new SpotMarketFeed();
        for (int i = 0; i < count; i++) {
            feed.publish("aws-us", i, 1.0, 0.1 + (i % 5) * 0.1);
        }
        assertThat(feed.tickCount("aws-us")).isEqualTo(count);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void spotPredictRounds(int rounds) {
        SpotRatePredictor predictor = new SpotRatePredictor();
        List<Double> rates = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            rates.add(0.1 + (i % 5) * 0.1);
        }
        assertThat(predictor.movingAverage(rates, 5))
                .isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 100})
    void hardenVolumes(int count) {
        IsolationPolicy policy = riskPolicy(count + 2, true);
        for (int i = 0; i < count; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        assertThat(new AdaptiveHardener().harden(policy, 30, audit))
                .isEqualTo(count);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void hardenRollbackVolumes(int rounds) {
        IsolationPolicy policy = riskPolicy(10, true);
        for (int i = 0; i < 5; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        AdaptiveHardener hardener = new AdaptiveHardener();
        for (int i = 0; i < rounds; i++) {
            hardener.harden(policy, 0, audit);
            hardener.rollback(policy, audit);
        }
        assertThat(hardener.revokedCount()).isZero();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 50, 100})
    void paretoFrontVolumes(int count) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(new Candidate("c" + i, 1 + i,
                    0.9, 0.1 + i * 0.001, 0.1 + i * 0.001));
        }
        assertThat(optimizer.paretoFront(candidates)).isNotEmpty();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void paretoChooseRounds(int rounds) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        var front = optimizer.paretoFront(List.of(
                new Candidate("a", 10, 0.9, 0.5, 0.1),
                new Candidate("b", 5, 0.5, 0.1, 0.9)));
        for (int i = 0; i < rounds; i++) {
            assertThat(optimizer.chooseByWeights(front,
                    1, 0, 0)).isNotNull();
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,0.9,0.1", "10,0.8,0.2", "20,0.7,0.3",
            "50,0.6,0.4", "100,0.5,0.5"})
    void paretoDominanceMix(int nodes, double slo, double cost) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        Candidate a = new Candidate("a", nodes, slo, cost, 0.1);
        Candidate b = new Candidate("b", nodes, slo - 0.1,
                cost + 0.1, 0.1);
        assertThat(optimizer.dominates(a, b)).isTrue();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void multiAgentRegionVolumes(int count) {
        MultiAgentAutonomy autonomy = autonomy(count);
        for (int i = 0; i < count; i++) {
            autonomy.record("r" + i, Action.RELAX, 1.0);
        }
        assertThat(autonomy.aggregate().get(Action.RELAX))
                .isGreaterThan(autonomy.aggregate()
                        .get(Action.TIGHTEN));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void autoTierRecordRounds(int rounds) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < rounds; i++) {
            manager.recordAccess("v1");
        }
        assertThat(manager.accessCount("v1")).isEqualTo(rounds);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void chainAnchorHeadRounds(int rounds) {
        ChainVerifier verifier = new ChainVerifier();
        for (int i = 0; i < rounds; i++) {
            AnchorRecord record = ChainAnchor.anchor("chain-1",
                    "block-1", i, "head-" + i);
            assertThat(verifier.verify(record, "head-" + i))
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void spotPredictVolumes(int count) {
        SpotRatePredictor predictor = new SpotRatePredictor();
        List<Double> rates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rates.add(0.1 + (i % 5) * 0.1);
        }
        assertThat(predictor.movingAverage(rates, 5))
                .isBetween(0.0, 1.0);
        assertThat(predictor.exponentialSmoothing(rates, 0.5))
                .isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void hardenTenantVolumes(int count) {
        IsolationPolicy policy = riskPolicy(count + 2, false);
        for (int i = 0; i < count; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        int revoked = new AdaptiveHardener().harden(policy,
                count * 10 + 10, audit);
        assertThat(revoked).isZero();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void hardenAuditRounds(int rounds) {
        IsolationPolicy policy = riskPolicy(10, true);
        policy.allow("t0", "t1");
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        AdaptiveHardener hardener = new AdaptiveHardener();
        for (int i = 0; i < rounds; i++) {
            hardener.harden(policy, 0, audit);
            hardener.rollback(policy, audit);
        }
        assertThat(audit.size()).isEqualTo(2L * rounds);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void paretoCandidateVolumes(int count) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(new Candidate("c" + i, 1 + i,
                    0.5 + i * 0.001, 0.5 - i * 0.001,
                    0.5 - i * 0.001));
        }
        assertThat(optimizer.paretoFront(candidates)).isNotEmpty();
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,0.9,0.1", "10,0.8,0.2", "20,0.7,0.3",
            "50,0.6,0.4", "100,0.5,0.5"})
    void paretoWeightMix(int nodes, double slo, double cost) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        var front = optimizer.paretoFront(List.of(
                new Candidate("a", nodes, slo, cost, 0.1),
                new Candidate("b", nodes / 2, slo * 0.5,
                        cost * 0.5, 0.9)));
        assertThat(optimizer.chooseByWeights(front,
                1, 0, 0)).isNotNull();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void multiAgentRecordRounds(int rounds) {
        MultiAgentAutonomy autonomy = autonomy(2);
        for (int i = 0; i < rounds; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
        }
        assertThat(autonomy.q("r0", Action.RELAX))
                .isGreaterThan(0.0);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void autoTierViewVolumes(int count) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < count; i++) {
            manager.recordAccess("v" + i);
            manager.decide("v" + i, 100, 10);
        }
        assertThat(manager.tiers()).hasSize(count);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void spotFeedReadRounds(int rounds) {
        SpotMarketFeed feed = new SpotMarketFeed();
        feed.publish("aws-us", 1, 1.0, 0.1);
        for (int i = 0; i < rounds; i++) {
            assertThat(feed.latest("aws-us").price())
                    .isEqualTo(1.0);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void paretoFrontSize(int count) {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(new Candidate("c" + i, 1 + i,
                    0.9 - i * 0.001, 0.1 + i * 0.001,
                    0.1 + i * 0.001));
        }
        assertThat(optimizer.paretoFront(candidates))
                .hasSize(1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void multiAgentQVolumes(int count) {
        MultiAgentAutonomy autonomy = autonomy(2);
        for (int i = 0; i < count; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
            autonomy.record("r1", Action.RELAX, 1.0);
        }
        Map<Action, Double> weights = autonomy.aggregate();
        assertThat(weights.get(Action.RELAX))
                .isGreaterThan(weights.get(Action.TIGHTEN));
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void autoTierResetVolumes(int count) {
        AutoTierManager manager = new AutoTierManager();
        for (int i = 0; i < count; i++) {
            manager.recordAccess("v" + i);
        }
        manager.resetCounts();
        assertThat(manager.viewIds()).isEmpty();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void chainAnchorHeadVolumes(int count) {
        ChainVerifier verifier = new ChainVerifier();
        for (int i = 0; i < count; i++) {
            AnchorRecord record = ChainAnchor.anchor("chain-1",
                    "block-" + i, i, "head");
            assertThat(verifier.verify(record)).isTrue();
        }
    }

    private static MultiAgentAutonomy autonomy(int count) {
        MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
        for (int i = 0; i < count; i++) {
            autonomy.registerRegion("r" + i, 0.5, 0.0, 10.0);
        }
        return autonomy;
    }

    private static IsolationPolicy riskPolicy(int count,
                                              boolean privateAll) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i,
                    privateAll));
        }
        return policy;
    }
}
