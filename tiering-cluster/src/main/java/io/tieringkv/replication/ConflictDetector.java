package io.tieringkv.replication;

import io.tieringkv.cdc.ChangeEvent;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 冲突检测（ADR-0108）：同 key 多来源写入标记冲突（主地域优先）。 */
public final class ConflictDetector {

    private final Map<ByteKey, String> originByKey = new ConcurrentHashMap<>();

    public boolean observe(ChangeEvent event, String originRegion) {
        if (event.type() == ChangeEvent.EventType.REGION_MOVE) {
            originByKey.remove(new ByteKey(event.key()));
            return false;
        }
        String previous = originByKey.putIfAbsent(
                new ByteKey(event.key()), originRegion);
        if (previous == null) {
            return false;
        }
        return !previous.equals(originRegion);
    }

    public boolean isConflicted(byte[] key) {
        return false; // 冲突按事件即时标记；查询用冲突日志
    }

    public void reset() {
        originByKey.clear();
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
            return Arrays.hashCode(key);
        }
    }
}
