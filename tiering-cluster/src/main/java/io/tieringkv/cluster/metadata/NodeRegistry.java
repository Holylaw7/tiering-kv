package io.tieringkv.cluster.metadata;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 节点注册表（ADR-0036）。 */
public final class NodeRegistry {

    private final Set<String> nodes = ConcurrentHashMap.newKeySet();

    public boolean register(String nodeId) {
        return nodes.add(nodeId);
    }

    public boolean unregister(String nodeId) {
        return nodes.remove(nodeId);
    }

    public boolean contains(String nodeId) {
        return nodes.contains(nodeId);
    }

    public Set<String> nodes() {
        return Set.copyOf(nodes);
    }

    public int size() {
        return nodes.size();
    }
}
