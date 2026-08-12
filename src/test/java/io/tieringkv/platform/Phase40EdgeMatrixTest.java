package io.tieringkv.platform;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.ChainAnchor;
import io.tieringkv.compliance.CrossChainAnchor;
import io.tieringkv.compliance.CrossChainVerifier;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.ObjectStorageArchive;
import io.tieringkv.datamesh.ObjectStorageArchive.ArchivedObject;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import io.tieringkv.observability.cost.SpotBidEngine;
import io.tieringkv.observability.cost.SpotBidEngine.BidResult;
import io.tieringkv.observability.cost.SpotMarketFeed;
import io.tieringkv.observability.cost.SpotMarketFeed.MarketTick;
import io.tieringkv.operations.slo.OnlineParetoRebalancer;
import io.tieringkv.operations.slo.ParetoCapacityOptimizer.Candidate;
import io.tieringkv.security.network.LearnedHardener;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 40 参数化边缘矩阵：拓扑/归档/跨链/竞价/学习/Pareto。 */
class Phase40EdgeMatrixTest {

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {2, 4, 6, 8, 10})
    void topologyAgentCounts(int count) {
        TopologyFederatedAutonomy autonomy = autonomy(count, 2);
        var weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> w > 0);
    }

    @ParameterizedTest(name = "groups {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void topologyGroupCounts(int groups) {
        TopologyFederatedAutonomy autonomy = autonomy(10, groups);
        var weights = autonomy.aggregate();
        assertThat(weights.values()).allMatch(w -> w > 0);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void topologyRounds(int rounds) {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        for (int i = 0; i < rounds; i++) {
            autonomy.record("r" + (i % 4), Action.RELAX, 1.0);
        }
        assertThat(autonomy.aggregate().get(Action.RELAX))
                .isGreaterThan(autonomy.aggregate()
                        .get(Action.TIGHTEN));
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.1, 0.3, 0.5, 0.8, 1.0})
    void topologyRates(double rate) {
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g0", rate, 0.0, 10.0);
        autonomy.record("r0", Action.RELAX, 1.0);
        assertThat(autonomy.q("r0", Action.RELAX)).isEqualTo(rate);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 1.5, 42.0, 100.0, 999.0})
    void archiveValues(double value) {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", "gcp-us", value), 1);
        assertThat(archive.download(object.objectKey())
                .orElseThrow().snapshot().value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"gcp-us", "aws-us"})
    void archiveClouds(String remoteCloud) {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", remoteCloud, 1), 1);
        assertThat(object.snapshot().remoteCloud())
                .isEqualTo(remoteCloud);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void archiveViewCounts(int count) {
        ObjectStorageArchive archive = archive("aws-us");
        for (int i = 0; i < count; i++) {
            archive.upload(snapshot("v" + i, "gcp-us", i), i);
        }
        assertThat(archive.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"aws-us", "aws-eu", "gcp-us", "azure-us"})
    void archiveStorageClouds(String storageCloud) {
        ObjectStorageArchive archive = archive(storageCloud);
        String remote = switch (storageCloud) {
            case "aws-eu" -> "gcp-us";
            case "azure-us" -> "aws-eu";
            default -> "gcp-us";
        };
        boolean reject = storageCloud.equals("aws-eu")
                || storageCloud.equals("azure-us");
        if (reject) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    SecurityException.class,
                    () -> archive.upload(snapshot("v1", remote, 1),
                            1));
        } else {
            assertThat(archive.upload(
                    snapshot("v1", remote, 1), 1).cloud())
                    .isEqualTo(storageCloud);
        }
    }

    @ParameterizedTest(name = "chains {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void crossChainCounts(int count) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        Set<String> chains = new java.util.LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            chains.add("chain-" + i);
        }
        var records = anchor.anchorAll(chains, 1, "head");
        assertThat(new CrossChainVerifier().verifyConsistent(
                records.values())).isTrue();
    }

    @ParameterizedTest(name = "chain {0}")
    @ValueSource(strings = {"ethereum", "solana", "polygon",
            "local", "anchor"})
    void crossChainNames(String chainId) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        var record = anchor.anchor(chainId, "block-1", 1, "head");
        assertThat(new CrossChainVerifier().verifyAny(
                List.of(record))).isTrue();
    }

    @ParameterizedTest(name = "head {0}")
    @ValueSource(strings = {"a", "hash-1", "head-x", "sha256"})
    void crossChainHeads(String head) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        var records = anchor.anchorAll(Set.of("a", "b"), 1, head);
        assertThat(new CrossChainVerifier().verifyConsistent(
                records.values())).isTrue();
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.0, 1.0, 2.0, 5.0, 10.0})
    void bidPrices(double price) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(tick(price, 0.2), price);
        assertThat(result.won()).isTrue();
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.5, 0.7, 0.9})
    void bidRates(double rate) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(tick(1.0, rate), 5.0);
        assertThat(result.won()).isEqualTo(rate <= 0.5);
    }

    @ParameterizedTest(name = "cap {0}")
    @ValueSource(doubles = {0.5, 1.0, 1.5, 3.0, 10.0})
    void bidCaps(double cap) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(tick(1.0, 0.2), cap);
        assertThat(result.won()).isEqualTo(cap >= 1.0);
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(doubles = {0.1, 0.3, 0.5, 0.7, 0.9})
    void bidMaxRates(double limit) {
        SpotBidEngine engine = new SpotBidEngine(limit);
        BidResult result = engine.bid(tick(1.0, 0.5), 5.0);
        assertThat(result.won()).isEqualTo(0.5 <= limit);
    }

    @ParameterizedTest(name = "step {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20})
    void learnedSteps(int step) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                step);
        assertThat(hardener.learn(true)).isEqualTo(50 - step);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void learnedRounds(int rounds) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                1);
        for (int i = 0; i < rounds; i++) {
            hardener.learn(true);
        }
        assertThat(hardener.threshold())
                .isEqualTo(Math.max(10, 50 - rounds));
    }

    @ParameterizedTest(name = "initial {0}")
    @ValueSource(ints = {0, 30, 50, 80, 200})
    void learnedInitials(int initial) {
        LearnedHardener hardener = new LearnedHardener(initial,
                10, 90, 5);
        assertThat(hardener.threshold()).isBetween(10, 90);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"10,5", "20,10", "50,25", "100,50", "200,100"})
    void learnedMix(int initial, int rounds) {
        LearnedHardener hardener = new LearnedHardener(initial,
                10, 90, 1);
        for (int i = 0; i < rounds; i++) {
            hardener.learn(i % 2 == 0);
        }
        assertThat(hardener.threshold()).isBetween(10, 90);
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 3, 5, 10, 50})
    void paretoLimits(int limit) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(limit);
        Candidate current = new Candidate("current", 10,
                0.5, 0.5, 0.5);
        var result = rebalancer.rebalance(List.of(
                current, new Candidate("better", 10 + limit,
                        0.9, 0.1, 0.1)), current, 1, 1, 1);
        assertThat(result.recommended()).isEqualTo("better");
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void paretoRounds(int rounds) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = new Candidate("c", 10, 0.5, 0.5, 0.5);
        for (int i = 0; i < rounds; i++) {
            rebalancer.rebalance(List.of(current), current,
                    1, 1, 1);
        }
        assertThat(rebalancer.history()).hasSize(rounds);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,0.9,0.1", "10,0.8,0.2", "20,0.7,0.3",
            "50,0.6,0.4", "100,0.5,0.5"})
    void paretoMix(int nodes, double slo, double cost) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = new Candidate("current", nodes,
                0.5, 0.5, 0.5);
        Candidate better = new Candidate("better", nodes + 5,
                slo, cost, 0.1);
        var result = rebalancer.rebalance(List.of(current, better),
                current, 1, 0, 0);
        assertThat(result.recommended()).isEqualTo("better");
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {2, 4, 8, 16, 32})
    void topologyLargeCounts(int count) {
        TopologyFederatedAutonomy autonomy = autonomy(count, 4);
        double sum = autonomy.aggregate().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void topologyAggregateRounds(int rounds) {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        for (int i = 0; i < rounds; i++) {
            autonomy.aggregate();
        }
        assertThat(autonomy.audit()).hasSize(rounds);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(longs = {0, 1, 100, 1000, 1_000_000})
    void archiveCounts(long value) {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", "gcp-us", value), 1);
        assertThat(archive.download(object.objectKey())
                .orElseThrow().snapshot().value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void archiveUploadRounds(int rounds) {
        ObjectStorageArchive archive = archive("aws-us");
        for (int i = 0; i < rounds; i++) {
            archive.upload(snapshot("v" + (i % 20),
                    "gcp-us", i), i);
        }
        assertThat(archive.size()).isEqualTo(Math.min(20, rounds));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void crossChainRounds(int rounds) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        CrossChainVerifier verifier = new CrossChainVerifier();
        for (int i = 0; i < rounds; i++) {
            var records = anchor.anchorAll(Set.of("a", "b"),
                    i, "head-" + i);
            assertThat(verifier.verifyConsistent(
                    records.values())).isTrue();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void bidRounds(int rounds) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        for (int i = 0; i < rounds; i++) {
            BidResult result = engine.bid(tick(1.0, 0.2), 1.5);
            assertThat(result.won()).isTrue();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void learnedAuditRounds(int rounds) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                1);
        for (int i = 0; i < rounds; i++) {
            hardener.learn(i % 2 == 0);
        }
        assertThat(hardener.audit()).hasSize(rounds);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"10,5,true", "20,10,false", "50,25,true",
            "100,50,false", "200,100,true"})
    void paretoMixRounds(int nodes, int limit, boolean within) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(limit);
        Candidate current = new Candidate("current", nodes,
                0.5, 0.5, 0.5);
        Candidate better = new Candidate("better",
                nodes + (within ? limit : limit + 1),
                0.9, 0.1, 0.1);
        var result = rebalancer.rebalance(List.of(current, better),
                current, 1, 1, 1);
        assertThat(result.recommended())
                .isEqualTo(within ? "better" : "current");
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"aws-us", "gcp-us", "azure-us"})
    void bidClouds(String cloud) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(
                new MarketTick(cloud, 1, 1.0, 0.2), 1.5);
        assertThat(result.cloud()).isEqualTo(cloud);
    }

    @ParameterizedTest(name = "chain {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void crossChainSizes(int count) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        for (int i = 0; i < count; i++) {
            anchor.anchor("chain-" + i, "block-" + i, i, "head");
        }
        assertThat(anchor.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyGroupSizes(int size) {
        TopologyFederatedAutonomy autonomy = autonomy(size * 2, 2);
        autonomy.aggregate();
        assertThat(autonomy.audit().get(0).groupSizes().values())
                .allMatch(value -> value == size);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void topologyRecordRounds(int rounds) {
        TopologyFederatedAutonomy autonomy = autonomy(2, 1);
        for (int i = 0; i < rounds; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
        }
        assertThat(autonomy.q("r0", Action.RELAX))
                .isGreaterThan(0.0);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void archiveDeleteRounds(int rounds) {
        ObjectStorageArchive archive = archive("aws-us");
        for (int i = 0; i < rounds; i++) {
            ArchivedObject object = archive.upload(
                    snapshot("v" + i, "gcp-us", i), i);
            archive.delete(object.objectKey());
        }
        assertThat(archive.size()).isZero();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void archiveReadRounds(int rounds) {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", "gcp-us", 1), 1);
        for (int i = 0; i < rounds; i++) {
            assertThat(archive.download(object.objectKey()))
                    .isPresent();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void crossChainVerifyRounds(int rounds) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        var records = anchor.anchorAll(Set.of("a", "b"), 1, "head");
        CrossChainVerifier verifier = new CrossChainVerifier();
        for (int i = 0; i < rounds; i++) {
            assertThat(verifier.verifyConsistent(
                    records.values())).isTrue();
        }
    }

    @ParameterizedTest(name = "chain {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void crossChainStorageRounds(int rounds) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        for (int i = 0; i < rounds; i++) {
            anchor.anchor("chain-" + i, "block", i, "head");
        }
        assertThat(anchor.size()).isEqualTo(rounds);
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"aws-us", "gcp-us", "azure-us",
            "aws-eu", "local"})
    void bidCloudVolumes(String cloud) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(
                new MarketTick(cloud, 1, 1.0, 0.2), 1.5);
        assertThat(engine.lastBid(cloud)).isPresent();
        assertThat(result.cloud()).isEqualTo(cloud);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void bidLastRounds(int rounds) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        for (int i = 0; i < rounds; i++) {
            engine.bid(tick(1.0, 0.2), 1.5);
        }
        assertThat(engine.lastBid("aws-us").orElseThrow().won())
                .isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void learnedBoundRounds(int rounds) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                5);
        for (int i = 0; i < rounds; i++) {
            hardener.learn(false);
        }
        assertThat(hardener.threshold())
                .isEqualTo(Math.min(90, 50 + rounds * 5));
    }

    @ParameterizedTest(name = "min {0}")
    @ValueSource(ints = {0, 10, 20, 50, 80})
    void learnedMins(int min) {
        LearnedHardener hardener = new LearnedHardener(50, min, 90,
                5);
        for (int i = 0; i < 100; i++) {
            hardener.learn(true);
        }
        assertThat(hardener.threshold()).isEqualTo(min);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void paretoHistoryRounds(int rounds) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = new Candidate("c", 10, 0.5, 0.5, 0.5);
        for (int i = 0; i < rounds; i++) {
            rebalancer.rebalance(List.of(current), current,
                    1, 1, 1);
        }
        assertThat(rebalancer.history()).hasSize(rounds);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"10,0.9", "20,0.8", "50,0.7", "100,0.6",
            "200,0.5"})
    void paretoCandidateMix(int nodes, double slo) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = new Candidate("current", nodes,
                0.5, 0.5, 0.5);
        Candidate better = new Candidate("better", nodes + 2,
                slo, 0.2, 0.1);
        var result = rebalancer.rebalance(List.of(current, better),
                current, 1, 0, 0);
        assertThat(result.recommended()).isEqualTo("better");
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {3, 6, 9, 12, 15})
    void topologyOddCounts(int count) {
        TopologyFederatedAutonomy autonomy = autonomy(count, 3);
        assertThat(autonomy.agentCount()).isEqualTo(count);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 0.25, 0.5, 0.75, 1.0})
    void archiveStaleFlags(double value) {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                new RemoteSnapshot("v1", "gcp-us", value, 1,
                        true, 1), 1);
        assertThat(object.snapshot().stale()).isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void crossChainAnyRounds(int rounds) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        CrossChainVerifier verifier = new CrossChainVerifier();
        for (int i = 0; i < rounds; i++) {
            var record = anchor.anchor("chain-1", "block",
                    i, "head");
            assertThat(verifier.verifyAny(List.of(record)))
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 0.2, 0.5, 0.8, 1.0})
    void bidInterruptionValues(double rate) {
        SpotBidEngine engine = new SpotBidEngine(rate);
        BidResult result = engine.bid(tick(1.0, rate), 2.0);
        assertThat(result.won()).isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void learnedThresholdRounds(int rounds) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                1);
        for (int i = 0; i < rounds; i++) {
            hardener.learn(i % 3 == 0);
        }
        assertThat(hardener.threshold()).isBetween(10, 90);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,10", "10,20", "20,50", "50,100", "100,200"})
    void paretoLimitMix(int nodes, int limit) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(limit);
        Candidate current = new Candidate("current", nodes,
                0.5, 0.5, 0.5);
        Candidate better = new Candidate("better", nodes + limit,
                0.9, 0.1, 0.1);
        var result = rebalancer.rebalance(List.of(current, better),
                current, 1, 1, 1);
        assertThat(result.recommended()).isEqualTo("better");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyRecordVolumes(int count) {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        for (int i = 0; i < count; i++) {
            autonomy.record("r" + (i % 4), Action.RELAX, 1.0);
        }
        assertThat(autonomy.aggregate().get(Action.RELAX))
                .isGreaterThan(0.3);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void archiveOverwriteRounds(int count) {
        ObjectStorageArchive archive = archive("aws-us");
        for (int i = 0; i < count; i++) {
            archive.upload(snapshot("v1", "gcp-us", i), i);
        }
        assertThat(archive.size()).isEqualTo(1);
        assertThat(archive.download("obj-v1").orElseThrow()
                .snapshot().value()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void bidCloudCounts(int count) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        for (int i = 0; i < count; i++) {
            engine.bid(new MarketTick("cloud-" + i, 1, 1.0,
                    0.2), 1.5);
        }
        assertThat(engine.lastBid("cloud-" + (count - 1)))
                .isPresent();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void learnedMaxCounts(int count) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                1);
        for (int i = 0; i < count; i++) {
            hardener.learn(false);
        }
        assertThat(hardener.threshold())
                .isEqualTo(Math.min(90, 50 + count));
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyAggregateVolumes(int count) {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        for (int i = 0; i < count; i++) {
            autonomy.aggregate();
        }
        assertThat(autonomy.audit()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void archiveMissingRounds(int count) {
        ObjectStorageArchive archive = archive("aws-us");
        for (int i = 0; i < count; i++) {
            assertThat(archive.download("missing-" + i)).isEmpty();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void crossChainStoreRounds(int count) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        for (int i = 0; i < count; i++) {
            anchor.anchorAll(Set.of("a", "b"), i, "head-" + i);
        }
        assertThat(anchor.size()).isEqualTo(2);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void bidRoundsVolumes(int count) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        for (int i = 0; i < count; i++) {
            engine.bid(tick(i * 0.1, 0.2), 5.0);
        }
        assertThat(engine.lastBid("aws-us")).isPresent();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void learnedAuditVolumes(int count) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                5);
        for (int i = 0; i < count; i++) {
            hardener.learn(true);
        }
        assertThat(hardener.audit()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void paretoRebalanceVolumes(int count) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = new Candidate("c", 10, 0.5, 0.5, 0.5);
        for (int i = 0; i < count; i++) {
            rebalancer.rebalance(List.of(current), current,
                    1, 1, 1);
        }
        assertThat(rebalancer.history()).hasSize(count);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"2,1", "4,2", "8,4", "16,8", "32,16"})
    void topologyGroupMix(int agents, int groups) {
        TopologyFederatedAutonomy autonomy = autonomy(agents,
                groups);
        assertThat(autonomy.agentCount()).isEqualTo(agents);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"aws-us,gcp-us,true", "aws-eu,gcp-us,false",
            "gcp-us,aws-us,true", "aws-us,aws-eu,false",
            "azure-us,gcp-us,true"})
    void archiveSovereigntyMix(String storage, String remote,
                               boolean allowed) {
        ObjectStorageArchive archive = archive(storage);
        if (allowed) {
            assertThat(archive.upload(
                    snapshot("v1", remote, 1), 1).cloud())
                    .isEqualTo(storage);
        } else {
            org.junit.jupiter.api.Assertions.assertThrows(
                    SecurityException.class,
                    () -> archive.upload(snapshot("v1", remote, 1),
                            1));
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"1.0,0.1,true", "1.0,0.6,false", "2.0,0.1,false",
            "0.5,0.1,true", "1.0,0.5,true"})
    void bidMix(double price, double rate, boolean won) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        BidResult result = engine.bid(tick(price, rate), 1.0);
        assertThat(result.won()).isEqualTo(won);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"10,5,true", "20,10,false", "50,25,true",
            "100,50,false", "200,100,true"})
    void paretoMixLimits(int nodes, int limit, boolean within) {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(limit);
        Candidate current = new Candidate("current", nodes,
                0.5, 0.5, 0.5);
        Candidate better = new Candidate("better",
                nodes + (within ? limit : limit + 1),
                0.9, 0.1, 0.1);
        var result = rebalancer.rebalance(List.of(current, better),
                current, 1, 0, 0);
        assertThat(result.recommended())
                .isEqualTo(within ? "better" : "current");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void crossChainAnyVolumes(int count) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        CrossChainVerifier verifier = new CrossChainVerifier();
        for (int i = 0; i < count; i++) {
            var record = anchor.anchor("chain-" + i, "block",
                    i, "head");
            assertThat(verifier.verifyAny(List.of(record)))
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void learnedThresholdVolumes(int count) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                2);
        for (int i = 0; i < count; i++) {
            hardener.learn(i % 2 == 0);
        }
        assertThat(hardener.threshold()).isBetween(10, 90);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyQVolumes(int count) {
        TopologyFederatedAutonomy autonomy = autonomy(2, 1);
        for (int i = 0; i < count; i++) {
            autonomy.record("r0", Action.RELAX, 1.0);
        }
        assertThat(autonomy.q("r0", Action.RELAX))
                .isGreaterThan(0.0);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void archiveListRounds(int count) {
        ObjectStorageArchive archive = archive("aws-us");
        for (int i = 0; i < count; i++) {
            archive.upload(snapshot("v" + i, "gcp-us", i), i);
        }
        assertThat(archive.objectKeys()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void crossChainConsistentVolumes(int count) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        CrossChainVerifier verifier = new CrossChainVerifier();
        for (int i = 0; i < count; i++) {
            var records = anchor.anchorAll(Set.of("a", "b"),
                    i, "head");
            assertThat(verifier.verifyConsistent(
                    records.values())).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void bidLastVolumes(int count) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        for (int i = 0; i < count; i++) {
            engine.bid(tick(1.0, 0.2), 1.0 + i * 0.1);
        }
        assertThat(engine.lastBid("aws-us").orElseThrow()
                .bidPrice()).isEqualTo(1.0 + (count - 1) * 0.1);
    }

    private static TopologyFederatedAutonomy autonomy(
            int agents, int groups) {
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        for (int i = 0; i < agents; i++) {
            autonomy.registerRegion("r" + i, "g" + (i % groups),
                    0.1, 0.0, 10.0);
        }
        return autonomy;
    }

    private static ObjectStorageArchive archive(String storageCloud) {
        return new ObjectStorageArchive(
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us",
                        "aws-eu", "eu", "azure-us", "us")),
                storageCloud);
    }

    private static RemoteSnapshot snapshot(String viewId,
                                           String remoteCloud,
                                           double value) {
        return new RemoteSnapshot(viewId, remoteCloud, value, 1,
                false, 1);
    }

    private static MarketTick tick(double price, double rate) {
        return new MarketTick("aws-us", 1, price, rate);
    }
}
