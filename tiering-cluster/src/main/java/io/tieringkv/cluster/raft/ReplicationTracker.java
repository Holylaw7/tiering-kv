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
        progress.put(peer, new FollowerProgress(peer, next, -1,
                System.nanoTime(), 0, -1));
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

    /** 记录一次异步发送（inflight+1，lastSentIndex 推进）。 */
    public void onSend(String peer, long sentUpTo) {
        progress.computeIfPresent(peer, (id, p) -> p.withSent(sentUpTo)
                .withInflight(p.inflight() + 1));
    }

    /** 记录一次响应（inflight-1）。 */
    public void onResponse(String peer) {
        progress.computeIfPresent(peer, (id, p) -> p.withInflight(p.inflight() - 1));
    }

    public int inflight(String peer) {
        FollowerProgress p = progress.get(peer);
        return p == null ? 0 : p.inflight();
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
