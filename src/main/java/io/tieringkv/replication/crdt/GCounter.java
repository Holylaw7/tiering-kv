package io.tieringkv.replication.crdt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** G-Counter（ADR-0114）：只增计数，合并取每节点最大值。 */
public final class GCounter {

    private final Map<String, Long> perNode = new ConcurrentHashMap<>();

    public void increment(String node) {
        perNode.merge(node, 1L, Long::sum);
    }

    public long value() {
        return perNode.values().stream().mapToLong(Long::longValue).sum();
    }

    public void merge(GCounter other) {
        other.perNode.forEach((node, count) -> perNode.merge(node, count,
                Math::max));
    }

    public Map<String, Long> snapshot() {
        return Map.copyOf(perNode);
    }
}
