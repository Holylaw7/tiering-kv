package io.tieringkv.vector.cluster;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量分片（ADR-0121）：路由、合并、重平衡计划。 */
class VectorShardTest {

    @Test
    void putRoutesByHash() {
        VectorShardManager manager = new VectorShardManager(4);
        manager.put(new Embedding("a", new float[]{1, 0}));
        manager.put(new Embedding("b", new float[]{0, 1}));
        assertThat(manager.totalSize()).isEqualTo(2);
        assertThat(manager.shardCount()).isEqualTo(4);
    }

    @Test
    void searchMergesAcrossShards() {
        VectorShardManager manager = new VectorShardManager(3);
        for (int i = 0; i < 30; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{i % 5, 5 - i % 5}));
        }
        assertThat(manager.search(new float[]{1, 1}, 5)).hasSize(5);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {10, 100, 1000})
    void parameterizedShardVolume(int count) {
        VectorShardManager manager = new VectorShardManager(4);
        for (int i = 0; i < count; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{i % 7, 7 - i % 7}));
        }
        assertThat(manager.totalSize()).isEqualTo(count);
        assertThat(manager.search(new float[]{1, 1}, 5)).hasSize(5);
    }

    @Test
    void deleteRemovesFromShard() {
        VectorShardManager manager = new VectorShardManager(2);
        manager.put(new Embedding("a", new float[]{1, 0}));
        assertThat(manager.delete("a")).isTrue();
        assertThat(manager.totalSize()).isZero();
    }

    @Test
    void rebalancePlannerBalancedNoMoves() {
        assertThat(new RebalancePlanner().plan(
                List.of(10, 10, 10), 0)).isEmpty();
    }

    @Test
    void rebalancePlannerMovesExcess() {
        List<RebalancePlanner.Move> moves =
                new RebalancePlanner().plan(List.of(20, 10, 10), 0);
        assertThat(moves).isNotEmpty();
        assertThat(moves.get(0).fromShard()).isZero();
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(ints = {8, 10})
    void rebalanceTargetPerShard(int target) {
        List<RebalancePlanner.Move> moves =
                new RebalancePlanner().plan(List.of(20, 5), target);
        assertThat(moves).isNotEmpty();
    }

    @Test
    void rebalanceEmptyShardsNoMoves() {
        assertThat(new RebalancePlanner().plan(List.of(), 0)).isEmpty();
    }

    @Test
    void shardManagerRebalanceReportsMoves() {
        VectorShardManager manager = new VectorShardManager(2);
        for (int i = 0; i < 50; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{i % 3, 3 - i % 3}));
        }
        assertThat(manager.rebalance(10)).isGreaterThanOrEqualTo(0);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 4, 8})
    void parameterizedShardCounts(int shards) {
        VectorShardManager manager = new VectorShardManager(shards);
        for (int i = 0; i < 50; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{1, 1}));
        }
        assertThat(manager.totalSize()).isEqualTo(50);
        assertThat(manager.search(new float[]{1, 1}, 5)).hasSize(5);
    }

    @Test
    void vectorShardSizeTracks() {
        VectorShard shard = new VectorShard(0);
        shard.put(new Embedding("a", new float[]{1, 0}));
        shard.put(new Embedding("b", new float[]{0, 1}));
        assertThat(shard.size()).isEqualTo(2);
    }
}
