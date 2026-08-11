package io.tieringkv.vector.cluster;

import java.util.ArrayList;
import java.util.List;

/** 分片重平衡计划（ADR-0121）：倾斜分片迁移。 */
public final class RebalancePlanner {

    public record Move(int fromShard, int toShard, int count) {
    }

    public List<Move> plan(List<Integer> shardSizes,
                           int targetPerShard) {
        List<Move> moves = new ArrayList<>();
        int total = shardSizes.stream().mapToInt(Integer::intValue).sum();
        int shards = shardSizes.size();
        if (shards == 0) {
            return moves;
        }
        int target = Math.max(1, total / shards);
        if (targetPerShard > 0) {
            target = Math.min(target, targetPerShard);
        }
        for (int i = 0; i < shards; i++) {
            int excess = shardSizes.get(i) - target;
            if (excess > 0) {
                for (int j = 0; j < shards && excess > 0; j++) {
                    if (i != j && shardSizes.get(j) < target) {
                        int room = target - shardSizes.get(j);
                        int move = Math.min(excess, room);
                        moves.add(new Move(i, j, move));
                        excess -= move;
                    }
                }
            }
        }
        return moves;
    }
}
