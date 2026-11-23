package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.CrossChainAnchor;
import io.tieringkv.compliance.CrossChainVerifier;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.ObjectStorageArchive;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import io.tieringkv.observability.cost.SpotBidEngine;
import io.tieringkv.observability.cost.SpotMarketFeed;
import io.tieringkv.operations.slo.OnlineParetoRebalancer;
import io.tieringkv.operations.slo.ParetoCapacityOptimizer.Candidate;
import io.tieringkv.security.network.LearnedHardener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Phase 40 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase40BenchmarkTest {

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {1000, 10000})
    void topologyAutonomyThroughput(int records) {
        TopologyFederatedAutonomy autonomy = autonomy(8, 2);
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            autonomy.record("r" + (i % 8), Action.RELAX,
                    i % 2 == 0 ? 1.0 : -1.0);
            if (i % 100 == 0) {
                autonomy.aggregate();
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE40-BENCH TOPO-AUTO %d -> %d ops/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "objects {0}")
    @ValueSource(ints = {1000, 5000})
    void objectArchiveThroughput(int objects) {
        ObjectStorageArchive archive = new ObjectStorageArchive(
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")),
                "aws-us");
        long start = System.nanoTime();
        for (int i = 0; i < objects; i++) {
            archive.upload(new RemoteSnapshot("v" + (i % 100),
                    "gcp-us", i, 1, false, i), i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE40-BENCH ARCHIVE %d -> %d ops/s%n",
                objects, objects * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "anchors {0}")
    @ValueSource(ints = {1000, 10000})
    void crossChainThroughput(int anchors) {
        CrossChainAnchor anchor = new CrossChainAnchor();
        CrossChainVerifier verifier = new CrossChainVerifier();
        Set<String> chains = Set.of("chain-1", "chain-2",
                "chain-3");
        long start = System.nanoTime();
        for (int i = 0; i < anchors; i++) {
            var records = anchor.anchorAll(chains, i, "head-" + i);
            org.junit.jupiter.api.Assertions.assertTrue(
                    verifier.verifyConsistent(records.values()));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE40-BENCH CROSS-CHAIN %d -> %d ops/s%n",
                anchors, anchors * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "bids {0}")
    @ValueSource(ints = {1000, 10000})
    void spotBidThroughput(int bids) {
        SpotBidEngine engine = new SpotBidEngine(0.5);
        SpotMarketFeed feed = new SpotMarketFeed();
        long start = System.nanoTime();
        for (int i = 0; i < bids; i++) {
            feed.publish("aws-us", i, 1.0, 0.1 + (i % 5) * 0.1);
            engine.bid(feed.latest("aws-us"), 2.0);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE40-BENCH SPOT-BID %d -> %d ops/s%n",
                bids, bids * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1000, 5000})
    void learnedHardeningThroughput(int rounds) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                1);
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            hardener.learn(i % 2 == 0);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE40-BENCH LEARN-HARDEN %d -> %d ops/s%n",
                rounds, rounds * 1_000L / elapsedMs);
    }

    @Test
    void onlineParetoThroughput() {
        OnlineParetoRebalancer rebalancer =
                new OnlineParetoRebalancer(5);
        Candidate current = new Candidate("current", 10,
                0.5, 0.5, 0.5);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(new Candidate("c" + i, 5 + i,
                    0.5 + i * 0.01, 0.5 - i * 0.01,
                    0.5 - i * 0.01));
        }
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            rebalancer.rebalance(candidates, current, 1, 1, 1);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE40-BENCH ONLINE-PARETO %d ms%n",
                elapsedMs);
    }

    @Test
    void topologyArchiveIntegration() {
        TopologyFederatedAutonomy autonomy = autonomy(4, 2);
        autonomy.aggregate();
        ObjectStorageArchive archive = new ObjectStorageArchive(
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")),
                "aws-us");
        archive.upload(new RemoteSnapshot("v1", "gcp-us", 1, 1,
                false, 1), 1);
        org.junit.jupiter.api.Assertions.assertEquals(1,
                archive.size());
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
}
