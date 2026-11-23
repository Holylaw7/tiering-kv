package io.tieringkv.platform;

import io.tieringkv.replication.crdt.GCounter;
import io.tieringkv.replication.crdt.GSet;
import io.tieringkv.replication.crdt.LwwRegister;
import io.tieringkv.replication.crdt.OrSet;
import io.tieringkv.dr.ConsistencyMode;
import io.tieringkv.dr.GlobalReadRouter;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.MeteredBilling;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.sql.AggregateType;
import io.tieringkv.sql.distributed.MergeAggregate;
import io.tieringkv.sql.distributed.PartialAggregate;
import io.tieringkv.sql.distributed.ShardPlan;
import io.tieringkv.sql.distributed.ShardPlanner;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.cluster.VectorShardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 29 最终边缘矩阵。 */
class Phase29FinalEdgeTest {

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100})
    void gCounterIncrementMatrix(int count) {
        GCounter counter = new GCounter();
        for (int i = 0; i < count; i++) {
            counter.increment("n");
        }
        assertThat(counter.value()).isEqualTo(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 100})
    void gSetElementsMatrix(int count) {
        GSet set = new GSet();
        for (int i = 0; i < count; i++) {
            set.add("e" + i);
        }
        assertThat(set.elements()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 50})
    void orSetElementsMatrix(int count) {
        OrSet set = new OrSet();
        for (int i = 0; i < count; i++) {
            set.add("k" + i, "t" + i);
        }
        assertThat(set.elements()).hasSize(count);
    }

    @ParameterizedTest(name = "ts {0}")
    @ValueSource(longs = {1, 7})
    void lwwTimestampMatrix(long ts) {
        LwwRegister register = new LwwRegister();
        register.set(ts, "n", bytes("v"));
        assertThat(register.timestamp()).isEqualTo(ts);
    }

    @ParameterizedTest(name = "partials {0}")
    @ValueSource(ints = {1, 5, 25})
    void mergeAggregateCounts(int count) {
        List<PartialAggregate> partials = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            partials.add(PartialAggregate.of(1));
        }
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.COUNT)).isEqualTo(count);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {2, 8})
    void shardPlannerDistribution(int shards) {
        ShardPlanner planner = new ShardPlanner();
        List<ShardPlan> plans = planner.plan(
                List.of("r1", "r2"), shards, "k");
        assertThat(plans).hasSize(shards);
        assertThat(plans).extracting(ShardPlan::region)
                .contains("r1", "r2");
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 4})
    void vectorShardManagerBoundaries(int shards) {
        VectorShardManager manager = new VectorShardManager(shards);
        manager.put(new Embedding("a", new float[]{1, 0}));
        assertThat(manager.totalSize()).isEqualTo(1);
        assertThat(manager.search(new float[]{1, 0}, 5)).hasSize(1);
    }

    @Test
    void emptyVectorShardSearchEmpty() {
        assertThat(new VectorShardManager(3)
                .search(new float[]{1, 1}, 5)).isEmpty();
    }

    @Test
    void shardPlanKeyBoundary() {
        ShardPlanner planner = new ShardPlanner();
        List<ShardPlan> plans = planner.plan(List.of("r1"), 2, "k");
        assertThat(plans.get(0).endKey()).isEqualTo(
                bytes("k000001"));
    }

    @Test
    void lwwMergeIdempotentTwice() {
        LwwRegister a = new LwwRegister();
        LwwRegister b = new LwwRegister();
        a.set(5, "n1", bytes("x"));
        b.set(9, "n2", bytes("y"));
        a.merge(b);
        a.merge(b);
        assertThat(a.value()).isEqualTo(bytes("y"));
    }

    @ParameterizedTest(name = "prefix {0}")
    @ValueSource(strings = {"a", "key-", "user:id:"})
    void shardPlanPrefixBoundaries(String prefix) {
        ShardPlanner planner = new ShardPlanner();
        assertThat(planner.plan(List.of("r1"), 2, prefix))
                .hasSize(2);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {2, 20})
    void gCounterMergeMaxMatrix(int count) {
        GCounter a = new GCounter();
        GCounter b = new GCounter();
        for (int i = 0; i < count; i++) {
            a.increment("n");
        }
        b.increment("n");
        a.merge(b);
        assertThat(a.value()).isEqualTo(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {2, 50})
    void gSetMergeMatrix(int count) {
        GSet a = new GSet();
        GSet b = new GSet();
        for (int i = 0; i < count; i++) {
            a.add("a" + i);
            b.add("b" + i);
        }
        a.merge(b);
        assertThat(a.size()).isEqualTo(count * 2);
    }

    @ParameterizedTest(name = "topK {0}")
    @ValueSource(ints = {1, 3})
    void vectorSearchTopKMatrix(int topK) {
        VectorShardManager manager = new VectorShardManager(2);
        for (int i = 0; i < 10; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{i % 3 + 1, 3 - i % 3}));
        }
        assertThat(manager.search(new float[]{1, 1}, topK))
                .hasSize(topK);
    }

    @Test
    void partialAggregateMinMax() {
        PartialAggregate partial = new PartialAggregate(2, 5, 2, 3);
        assertThat(partial.min()).isEqualTo(2);
        assertThat(partial.max()).isEqualTo(3);
    }

    @ParameterizedTest(name = "seq {0}")
    @ValueSource(longs = {0, 10, 50, 100, 500, 1000, Long.MAX_VALUE})
    void globalReadSeqBoundaries(long seq) {
        GlobalReadRouter router = new GlobalReadRouter(
                java.util.Map.of("a", Long.MAX_VALUE),
                region -> 10L, ConsistencyMode.BOUNDED);
        assertThat(router.route("a", seq)).isEqualTo("a");
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(longs = {0, 1, 10, 100, 1000, 10000, 100000})
    void usageMeterAmountMatrix(long amount) {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, amount);
        assertThat(meter.get(UsageMeter.MeterType.REQUESTS))
                .isEqualTo(amount);
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.0, 0.1, 1.0, 2.5, 10.0, 50.0, 100.0})
    void billingPriceBoundaries(double price) {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.STORAGE_GB, 2);
        BillingPlan plan = new BillingPlan("p", java.util.Map.of(
                UsageMeter.MeterType.STORAGE_GB, price));
        assertThat(new MeteredBilling().calculate(meter, plan))
                .isEqualTo(2 * price);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
