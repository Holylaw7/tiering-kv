package io.tieringkv.benchmark.mvcc;

import io.tieringkv.dr.ConsistencyMode;
import io.tieringkv.dr.GlobalReadRouter;
import io.tieringkv.replication.crdt.CrdtScaleSimulator;
import io.tieringkv.replication.crdt.HybridClockCalibrator;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.MeteredBilling;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.sql.AggregateType;
import io.tieringkv.sql.SqlEngine;
import io.tieringkv.sql.distributed.MergeAggregate;
import io.tieringkv.sql.distributed.PartialAggregate;
import io.tieringkv.sql.distributed.ShardPlanner;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.cluster.VectorShardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 29 基准（进程内口径，如实记录）。 */
class Phase29BenchmarkTest {

    @ParameterizedTest(name = "plans {0}")
    @ValueSource(ints = {100, 1000})
    void shardPlanThroughput(int count) {
        ShardPlanner planner = new ShardPlanner();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            planner.plan(List.of("r1", "r2", "r3"), 16, "k");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH SHARD-PLAN %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {100, 1000})
    void mergeAggregateThroughput(int count) {
        List<PartialAggregate> partials = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            partials.add(PartialAggregate.of(i));
        }
        long start = System.nanoTime();
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.SUM)).isGreaterThan(0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH MERGE-AGG %d -> %d ms%n",
                count, elapsedMs);
    }

    @ParameterizedTest(name = "vectors {0}")
    @ValueSource(ints = {100, 1000})
    void vectorShardSearchThroughput(int count) {
        VectorShardManager manager = new VectorShardManager(4);
        for (int i = 0; i < count; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{i % 5, 5 - i % 5}));
        }
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertThat(manager.search(new float[]{1, 1}, 5))
                    .hasSize(5);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH VSHARD %d -> %d ms%n",
                count, elapsedMs);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1_000, 100_000})
    void crdtScaleSimulation(int keys) {
        CrdtScaleSimulator simulator = new CrdtScaleSimulator(3, keys / 3);
        long start = System.nanoTime();
        simulator.run(5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH CRDT-SCALE %d -> %d ms%n",
                keys, elapsedMs);
        assertThat(simulator.registerCount())
                .isEqualTo(3 * (keys / 3));
    }

    @ParameterizedTest(name = "samples {0}")
    @ValueSource(ints = {100, 1000})
    void clockCalibrateThroughput(int count) {
        List<HybridClockCalibrator.Sample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            samples.add(new HybridClockCalibrator.Sample(i, i + 10));
        }
        long start = System.nanoTime();
        assertThat(new HybridClockCalibrator().estimateOffset(samples))
                .isEqualTo(10);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH CLOCK %d -> %d ms%n",
                count, elapsedMs);
    }

    @ParameterizedTest(name = "reads {0}")
    @ValueSource(ints = {100, 1000})
    void globalReadThroughput(int reads) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of("a", 10_000L), region -> 9_000L,
                ConsistencyMode.BOUNDED);
        long start = System.nanoTime();
        for (int i = 0; i < reads; i++) {
            router.route("a", i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH GLOBAL-READ %d -> %d ops/s%n",
                reads, reads * 1_000L / Math.max(1, elapsedMs));
    }

    @Test
    void meteredBillingLatency() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 1_000);
        BillingPlan plan = new BillingPlan("p", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.001));
        MeteredBilling billing = new MeteredBilling();
        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            billing.calculate(meter, plan);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH BILLING %d ms%n", elapsedMs);
    }

    @Test
    void sqlJoinDistributedEquivalent() {
        List<SqlEngine.Row> left = new ArrayList<>();
        List<SqlEngine.Row> right = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            left.add(new SqlEngine.Row(bytes("k" + i), bytes("L")));
            right.add(new SqlEngine.Row(bytes("k" + i), bytes("R")));
        }
        long start = System.nanoTime();
        assertThat(new SqlEngine().hashJoin(left, right,
                SqlEngine.Row::key, SqlEngine.Row::key)).hasSize(1_000);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE29-BENCH SQL-JOIN %d ms%n", elapsedMs);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
