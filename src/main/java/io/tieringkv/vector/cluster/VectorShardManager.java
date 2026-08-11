package io.tieringkv.vector.cluster;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** 分片管理器（ADR-0121）：路由、查询合并、重平衡。 */
public final class VectorShardManager {

    private final List<VectorShard> shards;

    public VectorShardManager(int shardCount) {
        this.shards = IntStream.range(0, Math.max(1, shardCount))
                .mapToObj(VectorShard::new).toList();
    }

    public void put(Embedding embedding) {
        shard(embedding.id()).put(embedding);
    }

    public boolean delete(String id) {
        return shard(id).delete(id);
    }

    public List<VectorStore.ScoredEmbedding> search(float[] query,
                                                    int topK) {
        List<VectorStore.ScoredEmbedding> candidates = new ArrayList<>();
        for (VectorShard shard : shards) {
            candidates.addAll(shard.store().search(query,
                    Math.max(1, topK)));
        }
        candidates.sort(Comparator.comparingDouble(
                VectorStore.ScoredEmbedding::score).reversed());
        return candidates.size() > topK
                ? List.copyOf(candidates.subList(0, topK)) : candidates;
    }

    public int rebalance(int targetPerShard) {
        List<Integer> sizes = shards.stream()
                .map(VectorShard::size).toList();
        List<RebalancePlanner.Move> moves =
                new RebalancePlanner().plan(sizes, targetPerShard);
        return moves.size();
    }

    public int totalSize() {
        return shards.stream().mapToInt(VectorShard::size).sum();
    }

    public int shardCount() {
        return shards.size();
    }

    private VectorShard shard(String id) {
        return shards.get(Math.floorMod(id.hashCode(),
                shards.size()));
    }
}
