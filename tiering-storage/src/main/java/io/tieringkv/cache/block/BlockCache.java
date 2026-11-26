package io.tieringkv.cache.block;

import io.tieringkv.memory.MemoryPool;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * SSTable Block Cache（ADR-0028 / B3 升级为自适应 16 分片 S3-FIFO 抗扫描缓存）：
 *
 * <p>基于 SOSP'23 S3-FIFO 算法：每个分片独立加锁并由 Small（10% 准入队列）、
 * Main（90% 热块队列）与 Ghost（幽灵再准入队列）组成——单次顺序扫描块在 Small
 * 队列快速淘汰而不污染 Main 热块，二次命中块晋升 Main 或经 Ghost 直入 Main；
 * 容量 &lt; 32 时退化为单分片以保持小容量全局精确语义。缓存体为池化 DirectByteBuffer，
 * 淘汰、表失效与清空时严格回池。
 */
public final class BlockCache {

    private static final int MAX_SHARDS = 16;
    private static final int MIN_ENTRIES_PER_SHARD = 16;
    private static final int MAX_FREQ = 3;

    private final CachePolicy policy;
    private final MemoryPool memoryPool;
    private final CacheStatistics statistics = new CacheStatistics();
    private final S3FifoShard[] shards;
    private final int shardMask;

    public BlockCache(CachePolicy policy, MemoryPool memoryPool) {
        this.policy = policy;
        this.memoryPool = memoryPool;
        int totalCapacity = Math.max(0, policy.capacity());
        int shardCount = computeShardCount(totalCapacity);
        this.shards = new S3FifoShard[shardCount];
        this.shardMask = shardCount - 1;
        int base = shardCount == 0 ? 0 : totalCapacity / shardCount;
        int remainder = shardCount == 0 ? 0 : totalCapacity % shardCount;
        for (int i = 0; i < shardCount; i++) {
            int cap = base + (i < remainder ? 1 : 0);
            this.shards[i] = new S3FifoShard(cap, memoryPool, statistics);
        }
    }

    private static int computeShardCount(int totalCapacity) {
        if (totalCapacity < MIN_ENTRIES_PER_SHARD * 2) {
            return 1;
        }
        int target = Math.min(MAX_SHARDS, totalCapacity / MIN_ENTRIES_PER_SHARD);
        return Math.max(1, Integer.highestOneBit(target));
    }

    /** 命中返回缓冲副本（position 0）；未命中返回 null。 */
    public ByteBuffer get(CacheKey key) {
        return shardFor(key).get(key);
    }

    public void put(CacheKey key, ByteBuffer data) {
        if (policy.capacity() <= 0) {
            return;
        }
        shardFor(key).put(key, data);
    }

    /** 失效某表的所有块（compaction 删除表时调用）。 */
    public void invalidate(long tableId) {
        for (S3FifoShard shard : shards) {
            shard.invalidate(tableId);
        }
    }

    public void clear() {
        for (S3FifoShard shard : shards) {
            shard.clear();
        }
    }

    public int size() {
        int total = 0;
        for (S3FifoShard shard : shards) {
            total += shard.size();
        }
        return total;
    }

    public int shardCount() {
        return shards.length;
    }

    public CacheStatistics statistics() {
        return statistics;
    }

    private S3FifoShard shardFor(CacheKey key) {
        if (shardMask == 0) {
            return shards[0];
        }
        int h = key.hashCode();
        h ^= (h >>> 16);
        return shards[h & shardMask];
    }

    /** 单分片 S3-FIFO 引擎：Small 10% + Main 90% + Ghost。 */
    private static final class S3FifoShard {

        private final int capacity;
        private final int smallCapacity;
        private final int mainCapacity;
        private final int ghostCapacity;
        private final MemoryPool memoryPool;
        private final CacheStatistics statistics;

        private final Map<CacheKey, Node> index;
        private final ArrayDeque<Node> smallQueue;
        private final ArrayDeque<Node> mainQueue;
        private final LinkedHashSet<CacheKey> ghostSet;

        S3FifoShard(int capacity, MemoryPool memoryPool, CacheStatistics statistics) {
            this.capacity = capacity;
            this.smallCapacity = capacity <= 0 ? 0 : Math.max(1, capacity / 10);
            this.mainCapacity = Math.max(0, capacity - this.smallCapacity);
            this.ghostCapacity = Math.max(1, this.mainCapacity);
            this.memoryPool = memoryPool;
            this.statistics = statistics;
            this.index = new HashMap<>(Math.max(16, capacity));
            this.smallQueue = new ArrayDeque<>(Math.max(4, this.smallCapacity + 2));
            this.mainQueue = new ArrayDeque<>(Math.max(8, this.mainCapacity + 2));
            this.ghostSet = new LinkedHashSet<>(Math.max(8, this.ghostCapacity + 2));
        }

        synchronized ByteBuffer get(CacheKey key) {
            Node node = index.get(key);
            if (node == null) {
                statistics.miss();
                return null;
            }
            if (node.freq < MAX_FREQ) {
                node.freq++;
            }
            statistics.hit();
            return node.buffer.duplicate();
        }

        synchronized void put(CacheKey key, ByteBuffer data) {
            if (capacity <= 0 || index.containsKey(key)) {
                return;
            }
            while (index.size() >= capacity) {
                if (!evictOne()) {
                    break;
                }
            }
            ByteBuffer copy = memoryPool.allocateRaw(data.remaining());
            copy.put(data.duplicate());
            copy.flip();
            Node node = new Node(key, copy);
            if (mainCapacity > 0 && ghostSet.remove(key)) {
                // Ghost 再准入：二次出现的冷块直接进入 Main 队列
                mainQueue.addLast(node);
            } else {
                smallQueue.addLast(node);
            }
            index.put(key, node);
        }

        private boolean evictOne() {
            while (!smallQueue.isEmpty() || !mainQueue.isEmpty()) {
                if (smallQueue.size() > smallCapacity || mainQueue.isEmpty()) {
                    if (evictFromSmall()) {
                        return true;
                    }
                } else {
                    if (evictFromMain()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean evictFromSmall() {
            while (!smallQueue.isEmpty()) {
                Node head = smallQueue.pollFirst();
                if (head.removed) {
                    continue;
                }
                if (head.freq > 0 && mainCapacity > 0) {
                    head.freq = 0;
                    mainQueue.addLast(head);
                    if (mainQueue.size() > mainCapacity) {
                        return evictFromMain();
                    }
                    // 若未满 Main 且 Small 还有剩余，继续在 Small 找 freq=0 候选
                    if (!smallQueue.isEmpty()) {
                        continue;
                    }
                    return evictFromMain();
                }
                evictNode(head, true);
                return true;
            }
            return false;
        }

        private boolean evictFromMain() {
            while (!mainQueue.isEmpty()) {
                Node head = mainQueue.pollFirst();
                if (head.removed) {
                    continue;
                }
                if (head.freq > 0) {
                    head.freq--;
                    mainQueue.addLast(head);
                } else {
                    evictNode(head, false);
                    return true;
                }
            }
            return false;
        }

        private void evictNode(Node node, boolean recordGhost) {
            node.removed = true;
            index.remove(node.key);
            memoryPool.release(node.buffer);
            statistics.eviction();
            if (recordGhost && mainCapacity > 0) {
                ghostSet.remove(node.key);
                ghostSet.add(node.key);
                while (ghostSet.size() > ghostCapacity) {
                    Iterator<CacheKey> it = ghostSet.iterator();
                    it.next();
                    it.remove();
                }
            }
        }

        synchronized void invalidate(long tableId) {
            boolean anyRemoved = false;
            Iterator<Map.Entry<CacheKey, Node>> it = index.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<CacheKey, Node> entry = it.next();
                if (entry.getKey().tableId() == tableId) {
                    Node node = entry.getValue();
                    node.removed = true;
                    memoryPool.release(node.buffer);
                    it.remove();
                    anyRemoved = true;
                }
            }
            if (anyRemoved) {
                smallQueue.removeIf(n -> n.removed);
                mainQueue.removeIf(n -> n.removed);
            }
            ghostSet.removeIf(k -> k.tableId() == tableId);
        }

        synchronized void clear() {
            for (Node node : index.values()) {
                node.removed = true;
                memoryPool.release(node.buffer);
            }
            index.clear();
            smallQueue.clear();
            mainQueue.clear();
            ghostSet.clear();
        }

        synchronized int size() {
            return index.size();
        }
    }

    private static final class Node {
        final CacheKey key;
        final ByteBuffer buffer;
        int freq;
        boolean removed;

        Node(CacheKey key, ByteBuffer buffer) {
            this.key = key;
            this.buffer = buffer;
        }
    }
}
