package io.tieringkv.storage.memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 byte[] 键的跳表（LevelDB MemTable 风格，ADR-0007 / B2 优化）。
 *
 * <p>非线程安全：每个实例由 MemTable 的一个分段锁保护，无需内部同步。
 * 键按无符号字典序排序（与 Redis 二进制键语义一致）。动态维护当前最大层级
 * {@code currentMaxLevel} 跳过空顶层遍历，并支持 O(log N) 零分配随机存活采样。
 */
public final class SkipList {

    private static final int MAX_LEVEL = 32;
    private static final int SAMPLE_SCAN_LIMIT = 4;

    private final Node head = new Node(null, MAX_LEVEL);
    // 可复用查找路径缓冲：Segment 写锁保证同一 SkipList 单线程修改
    private final Node[] updateBuffer = new Node[MAX_LEVEL];
    private int currentMaxLevel = 1;
    private volatile int size;

    public KeyValueEntry get(byte[] key) {
        Node node = findPredecessor(key).forward[0];
        return node != null && Arrays.compareUnsigned(node.key, key) == 0 ? node.entry : null;
    }

    /** 插入或覆盖已有键。 */
    public void put(KeyValueEntry entry) {
        putAndGetOld(entry);
    }

    /**
     * 插入并返回被覆盖的旧 entry（热路径优化：单次查找）。
     * 调用方负责内存计量（旧条目移除、新条目加入）。
     */
    public KeyValueEntry putAndGetOld(KeyValueEntry entry) {
        byte[] key = entry.key();
        Node[] update = updateBuffer;
        Node current = head;
        int top = currentMaxLevel;
        for (int level = top - 1; level >= 0; level--) {
            while (current.forward[level] != null
                    && Arrays.compareUnsigned(current.forward[level].key, key) < 0) {
                current = current.forward[level];
            }
            update[level] = current;
        }
        Node next = current.forward[0];
        if (next != null && Arrays.compareUnsigned(next.key, key) == 0) {
            KeyValueEntry old = next.entry;
            next.entry = entry;
            return old;
        }
        int level = randomLevel();
        if (level > top) {
            for (int i = top; i < level; i++) {
                update[i] = head;
            }
            currentMaxLevel = level;
        }
        Node node = new Node(key, level);
        node.entry = entry;
        for (int i = 0; i < level; i++) {
            node.forward[i] = update[i].forward[i];
            update[i].forward[i] = node;
        }
        size++;
        return null;
    }

    /** 移除键并返回原 entry；不存在返回 null。 */
    public KeyValueEntry remove(byte[] key) {
        Node[] update = updateBuffer;
        Node current = head;
        int top = currentMaxLevel;
        for (int level = top - 1; level >= 0; level--) {
            while (current.forward[level] != null
                    && Arrays.compareUnsigned(current.forward[level].key, key) < 0) {
                current = current.forward[level];
            }
            update[level] = current;
        }
        Node target = current.forward[0];
        if (target == null || Arrays.compareUnsigned(target.key, key) != 0) {
            return null;
        }
        for (int level = 0; level < top; level++) {
            if (update[level].forward[level] != target) {
                break;
            }
            update[level].forward[level] = target.forward[level];
        }
        while (currentMaxLevel > 1 && head.forward[currentMaxLevel - 1] == null) {
            currentMaxLevel--;
        }
        size--;
        return target.entry;
    }

    /**
     * O(log N) 零分配随机存活条目抽样（B2 近似 LFU 淘汰采样用）：
     * 从当前最高层随机前跳下降至第 0 层，无需全表扫描或额外辅助索引。
     */
    public KeyValueEntry sampleLive(long nowMillis) {
        if (size <= 0) {
            return null;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Node current = head;
        for (int level = currentMaxLevel - 1; level >= 0; level--) {
            int steps = rng.nextInt(3);
            for (int s = 0; s < steps; s++) {
                Node next = current.forward[level];
                if (next == null) {
                    break;
                }
                current = next;
            }
        }
        Node probe = (current == head) ? head.forward[0] : current;
        for (int i = 0; i < SAMPLE_SCAN_LIMIT && probe != null; i++, probe = probe.forward[0]) {
            if (probe.entry.isLive(nowMillis)) {
                return probe.entry;
            }
        }
        // 回绕至头部小步兜底，避免尾部连续 tombstone 错过存活键
        probe = head.forward[0];
        for (int i = 0; i < SAMPLE_SCAN_LIMIT && probe != null; i++, probe = probe.forward[0]) {
            if (probe.entry.isLive(nowMillis)) {
                return probe.entry;
            }
        }
        return null;
    }

    /** 按 key 升序返回全部 entry（含 tombstone），调用方负责过滤。 */
    public List<KeyValueEntry> entriesInOrder() {
        List<KeyValueEntry> result = new ArrayList<>(size);
        for (Node node = head.forward[0]; node != null; node = node.forward[0]) {
            result.add(node.entry);
        }
        return result;
    }

    public int size() {
        return size;
    }

    private Node findPredecessor(byte[] key) {
        Node current = head;
        for (int level = currentMaxLevel - 1; level >= 0; level--) {
            while (current.forward[level] != null
                    && Arrays.compareUnsigned(current.forward[level].key, key) < 0) {
                current = current.forward[level];
            }
        }
        return current;
    }

    /** 单条指令级几何分布(p=0.5)层级生成，替代循环 nextDouble()。 */
    private static int randomLevel() {
        int bits = ThreadLocalRandom.current().nextInt();
        return 1 + Integer.numberOfTrailingZeros(bits | (1 << (MAX_LEVEL - 1)));
    }

    private static final class Node {

        private final byte[] key;
        private KeyValueEntry entry;
        private final Node[] forward;

        private Node(byte[] key, int level) {
            this.key = key;
            this.forward = new Node[level];
        }
    }
}
