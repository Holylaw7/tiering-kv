package io.tieringkv.replication.crdt;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** G-Set（ADR-0114）：只增集合，合并取并集。 */
public final class GSet {

    private final Set<String> elements = ConcurrentHashMap.newKeySet();

    public void add(String element) {
        elements.add(element);
    }

    public boolean contains(String element) {
        return elements.contains(element);
    }

    public Set<String> elements() {
        return Set.copyOf(elements);
    }

    public void merge(GSet other) {
        elements.addAll(other.elements());
    }

    public int size() {
        return elements.size();
    }
}
