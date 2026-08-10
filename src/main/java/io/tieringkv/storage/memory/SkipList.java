package io.tieringkv.storage.memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 byte[] 键的跳表（LevelDB MemTable 风格，ADR-0007）。
 *
 * <p>非线程安全：每个实例由 MemTable 的一个分段锁保护，无需内部同步。
 * 键按无符号字典序排序（与 Redis 二进制键语义一致）。
 */
public final class SkipList {

    private static final int MAX_LEVEL = 32;
    private static final double PROMOTION_PROBABILITY = 0.5;

    private final Node head = new Node(null, MAX_LEVEL);
    private int size;

    public KeyValueEntry get(byte[] key) {
        Node node = findPredecessor(key).forward[0];
        return node != null && Arrays.compareUnsigned(node.key, key) == 0 ? node.entry : null;
    }

    /** 插入或覆盖已有键。 */
    public void put(KeyValueEntry entry) {
        byte[] key = entry.key();
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            while (current.forward[level] != null
                    && Arrays.compareUnsigned(current.forward[level].key, key) < 0) {
                current = current.forward[level];
            }
            update[level] = current;
        }
        Node next = current.forward[0];
        if (next != null && Arrays.compareUnsigned(next.key, key) == 0) {
            next.entry = entry;
            return;
        }
        int level = randomLevel();
        Node node = new Node(key, level);
        node.entry = entry;
        for (int i = 0; i < level; i++) {
            node.forward[i] = update[i].forward[i];
            update[i].forward[i] = node;
        }
        size++;
    }

    /** 移除键并返回原 entry；不存在返回 null。 */
    public KeyValueEntry remove(byte[] key) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
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
        for (int level = 0; level < MAX_LEVEL; level++) {
            if (update[level].forward[level] != target) {
                break;
            }
            update[level].forward[level] = target.forward[level];
        }
        size--;
        return target.entry;
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
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            while (current.forward[level] != null
                    && Arrays.compareUnsigned(current.forward[level].key, key) < 0) {
                current = current.forward[level];
            }
        }
        return current;
    }

    private static int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && ThreadLocalRandom.current().nextDouble() < PROMOTION_PROBABILITY) {
            level++;
        }
        return level;
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
