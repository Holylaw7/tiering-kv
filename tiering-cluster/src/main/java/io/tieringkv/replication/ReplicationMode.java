package io.tieringkv.replication;

/** 复制模式（ADR-0108）：ASYNC 即投即确认，SYNC 等待全部 ack。 */
public enum ReplicationMode {
    ASYNC,
    SYNC
}
