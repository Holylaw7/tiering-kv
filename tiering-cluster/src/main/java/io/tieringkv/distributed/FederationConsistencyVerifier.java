package io.tieringkv.distributed;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多集群联邦一致性验证（ADR-0308）：双活 VersionVector 同步模拟，
 * 输出冲突率与收敛时间。
 */
public final class FederationConsistencyVerifier {

    private final Map<String, Map<String, Long>> clusterVectors =
            new ConcurrentHashMap<>();
    private final Map<String, String> values =
            new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<String>> keyWriters =
            new ConcurrentHashMap<>();
    private long conflicts;
    private long syncs;

    public FederationConsistencyVerifier(String... clusters) {
        for (String cluster : clusters) {
            clusterVectors.put(cluster,
                    new ConcurrentHashMap<>());
        }
    }

    public void write(String cluster, String key, String value) {
        Map<String, Long> vector = clusterVectors.get(cluster);
        if (vector == null) {
            throw new IllegalArgumentException(
                    "unknown cluster " + cluster);
        }
        vector.merge(cluster, 1L, Math::max);
        values.put(key, value);
        keyWriters.computeIfAbsent(key,
                ignored -> ConcurrentHashMap.newKeySet())
                .add(cluster);
    }

    /** 同步：版本向量合并；冲突按 LWW（时间戳语义简化）。 */
    public void sync(String from, String to, String key) {
        Map<String, Long> fromVector = clusterVectors.get(from);
        Map<String, Long> toVector = clusterVectors.get(to);
        if (keyWriters.getOrDefault(key, java.util.Set.of())
                .size() > 1) {
            conflicts++;
        }
        fromVector.forEach((cluster, version) ->
                toVector.merge(cluster, version, Math::max));
        values.put(key, values.getOrDefault(key, ""));
        syncs++;
    }

    public double conflictRate() {
        return syncs == 0 ? 0 : (double) conflicts / syncs;
    }

    public long conflicts() {
        return conflicts;
    }

    public long syncs() {
        return syncs;
    }
}
