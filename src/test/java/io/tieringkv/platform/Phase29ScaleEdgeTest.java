package io.tieringkv.platform;

import io.tieringkv.replication.crdt.CrdtScaleSimulator;
import io.tieringkv.replication.crdt.GCounter;
import io.tieringkv.replication.crdt.GSet;
import io.tieringkv.replication.crdt.HybridClockCalibrator;
import io.tieringkv.replication.crdt.LwwRegister;
import io.tieringkv.replication.crdt.OrSet;
import io.tieringkv.sql.AggregateType;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 29 规模边缘：CRDT/分片/聚合参数矩阵。 */
class Phase29ScaleEdgeTest {

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {2, 6, 12})
    void crdtSimulatorNodes(int nodes) {
        CrdtScaleSimulator simulator = new CrdtScaleSimulator(nodes, 25);
        simulator.run(2);
        assertThat(simulator.registerCount()).isEqualTo(nodes * 25);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {10, 100})
    void gCounterKeyScale(int keys) {
        GCounter counter = new GCounter();
        for (int i = 0; i < keys; i++) {
            counter.increment("n");
        }
        assertThat(counter.value()).isEqualTo(keys);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {5, 500})
    void gSetAddBoundaries(int count) {
        GSet set = new GSet();
        for (int i = 0; i < count; i++) {
            set.add("e" + i);
        }
        assertThat(set.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {3, 300})
    void orSetBoundaries(int count) {
        OrSet set = new OrSet();
        for (int i = 0; i < count; i++) {
            set.add("k" + i, "t" + i);
        }
        assertThat(set.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "offset {0}")
    @ValueSource(longs = {-100, 100})
    void clockOffsetAdjustment(long offset) {
        HybridClockCalibrator calibrator = new HybridClockCalibrator();
        long adjusted = calibrator.adjust(5_000, offset);
        assertThat(adjusted).isEqualTo(5_000 - offset);
    }

    @Test
    void lwwScaleDeterministic() {
        LwwRegister register = new LwwRegister();
        for (int i = 0; i < 1000; i++) {
            register.set(i, "n" + (i % 3),
                    ("v" + i).getBytes(StandardCharsets.UTF_8));
        }
        assertThat(register.value()).isEqualTo(
                ("v999").getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "partials {0}")
    @ValueSource(ints = {2, 20, 100})
    void mergeAggregatePartialCounts(int count) {
        List<PartialAggregate> partials = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            partials.add(new PartialAggregate(1, i, 0, 0));
        }
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.SUM)).isEqualTo((long) count
                * (count - 1) / 2);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {2, 16})
    void shardPlannerShardBoundaries(int shards) {
        ShardPlanner planner = new ShardPlanner();
        assertThat(planner.plan(List.of("r1"), shards, "k"))
                .hasSize(shards);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {2, 6})
    void vectorShardScale(int shards) {
        VectorShardManager manager = new VectorShardManager(shards);
        for (int i = 0; i < 500; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{i % 3, 3 - i % 3}));
        }
        assertThat(manager.totalSize()).isEqualTo(500);
        assertThat(manager.search(new float[]{1, 1}, 5)).hasSize(5);
    }

    @Test
    void vectorShardRebalanceStable() {
        VectorShardManager manager = new VectorShardManager(3);
        for (int i = 0; i < 300; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{1, 1}));
        }
        int first = manager.rebalance(20);
        int second = manager.rebalance(20);
        assertThat(second).isLessThanOrEqualTo(first);
    }
}
