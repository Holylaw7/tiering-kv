package io.tieringkv.platform;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.CrossChainAnchor;
import io.tieringkv.compliance.CrossChainVerifier;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.ObjectStorageArchive;
import io.tieringkv.datamesh.ObjectStorageArchive.ArchivedObject;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import io.tieringkv.observability.cost.SpotBidEngine;
import io.tieringkv.observability.cost.SpotBidEngine.BidResult;
import io.tieringkv.observability.cost.SpotMarketFeed;
import io.tieringkv.operations.slo.OnlineParetoRebalancer;
import io.tieringkv.operations.slo.ParetoCapacityOptimizer.Candidate;
import io.tieringkv.security.network.LearnedHardener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 40 生产门禁（JVM 级）：拓扑/归档/跨链/竞价/学习/Pareto。 */
class Phase40ProductionGateTest {

    @Test
    void topologyAutonomyGate() {
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r1", "g0", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r2", "g0", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r3", "g1", 0.5, 0.0, 10.0);
        autonomy.registerRegion("r4", "g1", 0.5, 0.0, 10.0);
        for (int i = 0; i < 20; i++) {
            autonomy.record("r1", Action.RELAX, 1.0);
            autonomy.record("r2", Action.RELAX, 1.0);
            autonomy.record("r3", Action.RELAX, 1.0);
            autonomy.record("r4", Action.RELAX, 1.0);
        }
        var weights = autonomy.aggregate();
        assertThat(weights.get(Action.RELAX))
                .isGreaterThan(weights.get(Action.TIGHTEN));
        assertThat(autonomy.audit()).isNotEmpty();
    }

    @Test
    void objectArchiveGate() {
        ObjectStorageArchive archive = archive();
        ArchivedObject object = archive.upload(
                new RemoteSnapshot("v1", "gcp-us", 42, 1,
                        false, 1), 1);
        assertThat(archive.download(object.objectKey())
                .orElseThrow().snapshot().value()).isEqualTo(42);
    }

    @Test
    void objectArchiveSovereigntyGate() {
        ObjectStorageArchive archive = new ObjectStorageArchive(
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us",
                        "aws-eu", "eu")), "aws-eu");
        assertThatThrownBy(() -> archive.upload(
                new RemoteSnapshot("v1", "gcp-us", 1, 1,
                        false, 1), 1))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void crossChainGate() {
        CrossChainAnchor anchor = new CrossChainAnchor();
        var records = anchor.anchorAll(Set.of("chain-1",
                "chain-2"), 1000, "head");
        assertThat(new CrossChainVerifier().verifyConsistent(
                records.values())).isTrue();
    }

    @Test
    void crossChainTamperGate() {
        CrossChainAnchor anchor = new CrossChainAnchor();
        var records = anchor.anchorAll(Set.of("chain-1",
                "chain-2"), 1000, "head");
        records.put("chain-2", new io.tieringkv.compliance
                .ChainAnchor.AnchorRecord("chain-2", "block-x",
                1000, "head", "bad"));
        assertThat(new CrossChainVerifier().verifyConsistent(
                records.values())).isFalse();
    }

    @Test
    void spotBidGate() {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(
                new SpotMarketFeed.MarketTick("aws-us", 1, 1.0,
                        0.2), 1.5);
        assertThat(result.won()).isTrue();
        BidResult denied = engine.bid(
                new SpotMarketFeed.MarketTick("aws-us", 1, 2.0,
                        0.2), 1.0);
        assertThat(denied.won()).isFalse();
    }

    @Test
    void learnedHardeningGate() {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                5);
        hardener.learn(true);
        assertThat(hardener.threshold()).isEqualTo(45);
        hardener.learn(false);
        assertThat(hardener.threshold()).isEqualTo(50);
    }

    @Test
    void onlineParetoGate() {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = new Candidate("current", 10,
                0.5, 0.5, 0.5);
        var result = rebalancer.rebalance(List.of(
                current, new Candidate("better", 12, 0.9,
                        0.1, 0.1)), current, 1, 1, 1);
        assertThat(result.recommended()).isEqualTo("better");
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {2, 6, 10})
    void parameterizedTopologyAgents(int count) {
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        for (int i = 0; i < count; i++) {
            autonomy.registerRegion("r" + i, "g" + (i % 2),
                    0.1, 0.0, 10.0);
        }
        var weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> w > 0);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 5.0, 100.0})
    void parameterizedArchiveValues(double value) {
        ObjectStorageArchive archive = archive();
        ArchivedObject object = archive.upload(
                new RemoteSnapshot("v1", "gcp-us", value, 1,
                        false, 1), 1);
        assertThat(archive.download(object.objectKey())
                .orElseThrow().snapshot().value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.1, 0.4, 0.6})
    void parameterizedBidRates(double rate) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(
                new SpotMarketFeed.MarketTick("aws-us", 1, 1.0,
                        rate), 2.0);
        assertThat(result.won()).isEqualTo(rate <= 0.5);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedLearningRounds(int rounds) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                1);
        for (int i = 0; i < rounds; i++) {
            hardener.learn(true);
        }
        assertThat(hardener.threshold())
                .isEqualTo(Math.max(10, 50 - rounds));
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 5, 50})
    void parameterizedParetoLimits(int limit) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(limit);
        Candidate current = new Candidate("current", 10,
                0.5, 0.5, 0.5);
        var result = rebalancer.rebalance(List.of(
                current, new Candidate("better", 10 + limit,
                        0.9, 0.1, 0.1)), current, 1, 1, 1);
        assertThat(result.recommended()).isEqualTo("better");
    }

    private static ObjectStorageArchive archive() {
        return new ObjectStorageArchive(
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")),
                "aws-us");
    }
}
