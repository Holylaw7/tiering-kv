package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.MultiAgentAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.ChainAnchor;
import io.tieringkv.compliance.ChainVerifier;
import io.tieringkv.datamesh.AutoTierManager;
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

import java.util.ArrayList;
import java.util.List;

/** Phase 39 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase39BenchmarkTest {

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {1000, 10000})
    void multiAgentThroughput(int records) {
        MultiAgentAutonomy autonomy = autonomy(4);
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            String region = "r" + (i % 4);
            autonomy.record(region, Action.RELAX,
                    i % 2 == 0 ? 1.0 : -1.0);
            if (i % 100 == 0) {
                autonomy.aggregate();
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE39-BENCH MULTI-AGENT %d -> %d ops/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "anchors {0}")
    @ValueSource(ints = {1000, 10000})
    void chainAnchorThroughput(int anchors) {
        ChainVerifier verifier = new ChainVerifier();
        long start = System.nanoTime();
        for (int i = 0; i < anchors; i++) {
            var record = ChainAnchor.anchor("chain-1",
                    "block-" + i, i, "head-" + i);
            org.junit.jupiter.api.Assertions.assertTrue(
                    verifier.verify(record));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE39-BENCH ANCHOR %d -> %d ops/s%n",
                anchors, anchors * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "access {0}")
    @ValueSource(ints = {1000, 10000})
    void autoTierThroughput(int access) {
        AutoTierManager manager = new AutoTierManager();
        long start = System.nanoTime();
        for (int i = 0; i < access; i++) {
            manager.recordAccess("v" + (i % 100));
            if (i % 100 == 0) {
                manager.decide("v" + (i % 100), 500, 100);
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE39-BENCH AUTO-TIER %d -> %d ops/s%n",
                access, access * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "ticks {0}")
    @ValueSource(ints = {1000, 5000})
    void spotPredictionThroughput(int ticks) {
        SpotMarketFeed feed = new SpotMarketFeed();
        SpotRatePredictor predictor = new SpotRatePredictor();
        List<Double> rates = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            double rate = 0.1 + (i % 10) * 0.01;
            feed.publish("aws-us", i, 1.0, rate);
            rates.add(rate);
        }
        long start = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            predictor.movingAverage(rates, 10);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE39-BENCH SPOT-PRED %d -> %d ops/s%n",
                ticks, ticks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1000, 5000})
    void hardeningThroughput(int pairs) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i <= pairs; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        AdaptiveHardener hardener = new AdaptiveHardener();
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        long start = System.nanoTime();
        hardener.harden(policy, 0, audit);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE39-BENCH HARDEN %d -> %d pairs/s%n",
                pairs, pairs * 1_000L / elapsedMs);
    }

    @Test
    void paretoThroughput() {
        ParetoCapacityOptimizer optimizer =
                new ParetoCapacityOptimizer();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            candidates.add(new Candidate("c" + i, 1 + i,
                    0.9 - i * 0.005, 0.1 + i * 0.005,
                    0.1 + i * 0.005));
        }
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            optimizer.paretoFront(candidates);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE39-BENCH PARETO %d ms%n",
                elapsedMs);
    }

    @Test
    void attestationAnchorIntegration() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1);
        var head = chain.attestations().get(
                chain.attestations().size() - 1).hash();
        var record = ChainAnchor.anchor("chain-1", "block-1",
                1, head);
        org.junit.jupiter.api.Assertions.assertTrue(
                new ChainVerifier().verify(record, head));
    }

    private static MultiAgentAutonomy autonomy(int count) {
        MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
        for (int i = 0; i < count; i++) {
            autonomy.registerRegion("r" + i, 0.1, 0.0, 10.0);
        }
        return autonomy;
    }
}
