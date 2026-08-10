package io.tieringkv.cluster.raft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 复制进度跟踪（ADR-0042）：每个 follower 的 nextIndex / matchIndex /
 * lastAck；匹配索引初始 -1，防止"未复制即视为匹配"。
 */
public final class ReplicationTracker {

    private final Map<String, FollowerProgress> progress = new ConcurrentHashMap<>();

    public void initialize(String peer, long next) {
        progress.put(peer, new FollowerProgress(peer, next, -1, System.nanoTime()));
    }

    public long nextIndex(String peer) {
        FollowerProgress p = progress.get(peer);
        return p == null ? 0 : p.nextIndex();
    }

    public long matchIndex(String peer) {
        FollowerProgress p = progress.get(peer);
        return p == null ? -1 : p.matchIndex();
    }

    public void onSuccess(String peer, long match) {
        progress.computeIfPresent(peer, (id, p) -> p.withMatch(match));
    }

    public void onFailure(String peer) {
        progress.computeIfPresent(peer, (id, p) -> p.withFailure());
    }

    public FollowerProgress progress(String peer) {
        return progress.get(peer);
    }

    public Map<String, Long> matchIndexSnapshot() {
        java.util.HashMap<String, Long> snapshot = new java.util.HashMap<>();
        progress.forEach((id, p) -> snapshot.put(id, p.matchIndex()));
        return snapshot;
    }
}
