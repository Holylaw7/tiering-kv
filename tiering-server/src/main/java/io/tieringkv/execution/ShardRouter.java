package io.tieringkv.execution;

/** Key → 分片路由（ADR-0023 / B1 优化）：FNV-1a 32 哈希取模（2 的幂走位掩码零除法）。 */
public final class ShardRouter {

    private ShardRouter() {
    }

    public static int route(byte[] key, int shardCount) {
        int hash = fnv1a32(key);
        if ((shardCount & (shardCount - 1)) == 0) {
            return hash & (shardCount - 1);
        }
        return Math.floorMod(hash, shardCount);
    }

    private static int fnv1a32(byte[] data) {
        int hash = 0x811c9dc5;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash;
    }
}
