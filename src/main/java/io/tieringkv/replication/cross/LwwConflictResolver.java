package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LWW 冲突决策（ADR-0321）：高 timestamp 胜；同 timestamp 按源
 * cluster id 字典序胜；同 region 的 seq 幂等（重放安全）。
 */
public final class LwwConflictResolver {

    private final Map<ByteKey, KeyState> applied =
            new ConcurrentHashMap<>();

    /** 返回 true 表示该事件应被应用（比当前状态更新）。 */
    public boolean accept(ChangeEvent event, String originClusterId) {
        if (originClusterId == null) {
            throw new IllegalArgumentException(
                    "originClusterId required");
        }
        ByteKey key = new ByteKey(event.key());
        KeyState current = applied.get(key);
        KeyState candidate = new KeyState(event.regionId(), event.seq(),
                event.timestamp(), originClusterId);
        if (current == null) {
            applied.put(key, candidate);
            return true;
        }
        if (current.regionId().equals(candidate.regionId())
                && current.seq() >= candidate.seq()) {
            return false; // 同源重放/旧事件
        }
        if (candidate.timestamp() > current.timestamp()
                || (candidate.timestamp() == current.timestamp()
                && candidate.clusterId()
                .compareTo(current.clusterId()) > 0)) {
            applied.put(key, candidate);
            return true;
        }
        return false;
    }

    public int appliedSize() {
        return applied.size();
    }

    private record KeyState(String regionId, long seq,
                            long timestamp, String clusterId) {
    }

    private record ByteKey(byte[] key) {
        private ByteKey {
            key = key.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteKey that
                    && Arrays.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(key));
        }
    }
}
