package io.tieringkv.cluster.raft;

/** follower 复制进度（ADR-0042）：nextIndex / matchIndex / 最近确认时间。 */
public record FollowerProgress(
        String peerId,
        long nextIndex,
        long matchIndex,
        long lastAckNanos) {

    public FollowerProgress withNext(long next) {
        return new FollowerProgress(peerId, next, matchIndex, lastAckNanos);
    }

    public FollowerProgress withMatch(long match) {
        return new FollowerProgress(peerId, match + 1, match, System.nanoTime());
    }

    public FollowerProgress withFailure() {
        return new FollowerProgress(peerId, Math.max(0, nextIndex - 1), matchIndex, lastAckNanos);
    }
}
