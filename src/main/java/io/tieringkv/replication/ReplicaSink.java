package io.tieringkv.replication;

import io.tieringkv.cdc.ChangeEvent;

import java.util.concurrent.CompletableFuture;

/** 地域副本投递目标（ADR-0108）：ASYNC 立即完成，SYNC 等待 ack。 */
public interface ReplicaSink {

    CompletableFuture<Void> apply(ChangeEvent event);

    String replicaId();
}
