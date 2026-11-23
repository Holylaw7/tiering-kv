package io.tieringkv.execution;

/** Key → 分片路由（ADR-0023）：FNV-1a 32 哈希取模。 */
public final class ShardRouter {

    private ShardRouter() {
    }

    public static int route(byte[] key, int shardCount) {
        return Math.floorMod(fnv1a32(key), shardCount);
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
