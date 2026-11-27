package io.tieringkv.storage.wal;

/**
 * WAL 分片与锁条带路由（v6.5.0 Phase 1）：
 * 采用与 {@code ShardRouter} 完全一致的 FNV-1a 32-bit 哈希算法。
 *
 * <p>当 {@code walShards == workerShards}（均为 2 的幂，例如 16）时，
 * 同一个 {@code ShardWorker} 处理的热键 100% 落入同一个 {@code WALShard}，
 * 且所涉及的 256 个 keyLock 条带在各分片间严格互斥不相交，从根本上消除跨核锁争用。
 */
public final class WalShardRouter {

    private WalShardRouter() {
    }

    public static int route(byte[] key, int shardCount) {
        if (shardCount <= 1) {
            return 0;
        }
        int hash = fnv1a32(key);
        if (isPowerOfTwo(shardCount)) {
            return hash & (shardCount - 1);
        }
        return Math.floorMod(hash, shardCount);
    }

    /** 256 桶 keyLock 条带索引（低 8 位 FNV-1a）。 */
    public static int keyStripe(byte[] key) {
        return fnv1a32(key) & 255;
    }

    public static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    public static int fnv1a32(byte[] data) {
        int hash = 0x811c9dc5;
        if (data != null) {
            for (byte b : data) {
                hash ^= (b & 0xff);
                hash *= 0x01000193;
            }
        }
        return hash;
    }
}
