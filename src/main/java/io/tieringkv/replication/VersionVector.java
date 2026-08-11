package io.tieringkv.replication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 版本向量（ADR-0114）：因果检测与环回抑制。 */
public final class VersionVector {

    private final Map<String, Long> versions = new ConcurrentHashMap<>();

    public void bump(String node) {
        versions.merge(node, 1L, Math::max);
    }

    public void observe(String node, long version) {
        versions.merge(node, version, Math::max);
    }

    public long version(String node) {
        return versions.getOrDefault(node, 0L);
    }

    /** 事件是否已见（版本 <= 本地向量对应项）。 */
    public boolean seen(String node, long version) {
        return version <= version(node);
    }

    public void merge(VersionVector other) {
        other.versions.forEach((node, version) ->
                versions.merge(node, version, Math::max));
    }

    public Map<String, Long> snapshot() {
        return Map.copyOf(versions);
    }
}
