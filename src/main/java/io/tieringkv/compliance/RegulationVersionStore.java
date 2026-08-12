package io.tieringkv.compliance;

import io.tieringkv.compliance.RegulationVersion.Version;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** 法规版本库（ADR-0159）：按生效时间取有效版本 + 历史。 */
public final class RegulationVersionStore {

    private final Map<String, TreeMap<Long, Version>> versions =
            new ConcurrentHashMap<>();

    public synchronized void register(Version version) {
        if (version == null) {
            throw new IllegalArgumentException(
                    "version required");
        }
        versions.computeIfAbsent(version.regulation(),
                ignored -> new TreeMap<>())
                .put(version.effectiveFromMillis(), version);
    }

    /** 当前时间生效的最新版本。 */
    public synchronized Optional<Version> effective(
            String regulation, long nowMillis) {
        TreeMap<Long, Version> map = versions.get(regulation);
        if (map == null) {
            return Optional.empty();
        }
        Map.Entry<Long, Version> entry =
                map.floorEntry(nowMillis);
        return entry == null ? Optional.empty()
                : Optional.of(entry.getValue());
    }

    public synchronized List<Version> history(String regulation) {
        TreeMap<Long, Version> map = versions.get(regulation);
        return map == null ? List.of()
                : List.copyOf(map.values());
    }

    /** 切换版本：以指定生效时间发布目标版本控制项的新版本。 */
    public synchronized void activate(String regulation,
                                      String versionId,
                                      long effectiveFromMillis) {
        Version target = history(regulation).stream()
                .filter(version -> version.versionId()
                        .equals(versionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown version " + versionId));
        register(new Version(regulation,
                versionId + "@" + effectiveFromMillis,
                effectiveFromMillis, target.controls()));
    }

    public synchronized int versionCount(String regulation) {
        TreeMap<Long, Version> map = versions.get(regulation);
        return map == null ? 0 : map.size();
    }
}
