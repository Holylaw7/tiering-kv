package io.tieringkv.replication.crdt;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** OR-Set（ADR-0114）：可增删集合，删除带唯一 tag，合并收敛。 */
public final class OrSet {

    private final Set<Tagged> adds = ConcurrentHashMap.newKeySet();
    private final Set<Tagged> removes = ConcurrentHashMap.newKeySet();

    public record Tagged(String element, String tag) {
    }

    public void add(String element, String tag) {
        adds.add(new Tagged(element, tag));
    }

    public void remove(String element, String tag) {
        removes.add(new Tagged(element, tag));
    }

    public boolean contains(String element) {
        return adds.stream().anyMatch(tagged ->
                tagged.element().equals(element)
                        && !removes.contains(tagged));
    }

    public Set<String> elements() {
        Set<String> result = new HashSet<>();
        for (Tagged tagged : adds) {
            if (!removes.contains(tagged)) {
                result.add(tagged.element());
            }
        }
        return Set.copyOf(result);
    }

    public void merge(OrSet other) {
        adds.addAll(other.adds);
        removes.addAll(other.removes);
    }

    public int size() {
        return elements().size();
    }
}
