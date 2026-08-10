package io.tieringkv.cluster.raft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 复制进度跟踪（ADR-0037）：nextIndex / matchIndex。 */
public final class ReplicationManager {

    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    public void initialize(String peer, long next) {
        nextIndex.put(peer, next);
        // -1 表示尚无任何已确认匹配（日志索引从 0 开始，0 会被误判为已匹配）
        matchIndex.put(peer, -1L);
    }

    public long nextIndex(String peer) {
        return nextIndex.getOrDefault(peer, 0L);
    }

    public long matchIndex(String peer) {
        return matchIndex.getOrDefault(peer, -1L);
    }

    public void onSuccess(String peer, long match) {
        matchIndex.put(peer, match);
        nextIndex.put(peer, match + 1);
    }

    public void onFailure(String peer) {
        nextIndex.put(peer, Math.max(0, nextIndex(peer) - 1));
    }

    public Map<String, Long> matchIndexSnapshot() {
        return Map.copyOf(matchIndex);
    }
}
