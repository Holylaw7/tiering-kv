package io.tieringkv.cluster.raft;

/**
 * 提交通知去重（ADR-0042）：commitIndex 推进后立即补发心跳，
 * 同一索引只通知一次，避免提交路径消息风暴。
 */
public final class CommitNotifier {

    private long lastNotified = -1;

    /** 返回 true 表示该 commitIndex 需要立即补发。 */
    public synchronized boolean mark(long commitIndex) {
        if (commitIndex <= lastNotified) {
            return false;
        }
        lastNotified = commitIndex;
        return true;
    }
}
