package io.tieringkv.transaction.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.cross.ConflictResolver;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨集群事务参与者（ADR-0339）：TXN_PREPARE 暂存（不落盘），
 * TXN_COMMIT 按 LWW 决策应用（commitTS 冲突收敛，同 seq 重放幂等），
 * TXN_ROLLBACK 丢弃暂存；COMMIT 无 PREPARE（恢复重放）直接应用。
 */
public final class CrossClusterTxnParticipant {

    private final StorageEngine storage;
    private final ConflictResolver resolver;
    private final Map<String, Map<ByteArrayKey, Staged>> pending =
            new ConcurrentHashMap<>();

    public CrossClusterTxnParticipant(StorageEngine storage,
                                      ConflictResolver resolver) {
        if (storage == null || resolver == null) {
            throw new IllegalArgumentException(
                    "storage and resolver required");
        }
        this.storage = storage;
        this.resolver = resolver;
    }

    /** 返回 true 表示阶段事件被接受。 */
    public boolean onEvent(ChangeEvent event, String originClusterId) {
        return switch (event.type()) {
            case TXN_PREPARE -> stage(event);
            case TXN_COMMIT -> commit(event, originClusterId);
            case TXN_ROLLBACK -> rollback(event);
            default -> false;
        };
    }

    public int pendingSize() {
        return pending.size();
    }

    private boolean stage(ChangeEvent event) {
        if (event.txnId() == null || event.key() == null
                || (event.value() == null && !event.deleted())) {
            return false;
        }
        pending.computeIfAbsent(event.txnId(), ignored ->
                        new ConcurrentHashMap<>())
                .put(new ByteArrayKey(event.key()),
                        new Staged(event.key(), event.value(),
                                event.deleted()));
        return true;
    }

    private boolean commit(ChangeEvent event, String originClusterId) {
        Map<ByteArrayKey, Staged> staged = pending.remove(
                event.txnId());
        if (staged == null || staged.isEmpty()) {
            if (resolver.accept(event, originClusterId)) {
                write(event);
            }
            return true;
        }
        for (Staged item : staged.values()) {
            ChangeEvent commitEvent = new ChangeEvent(event.seq(),
                    ChangeEvent.EventType.TXN_COMMIT, item.key(),
                    item.value(), item.deleted(), event.txnId(),
                    event.regionId(), event.timestamp());
            if (resolver.accept(commitEvent, originClusterId)) {
                write(commitEvent);
            }
        }
        return true;
    }

    private boolean rollback(ChangeEvent event) {
        pending.remove(event.txnId());
        return true;
    }

    private void write(ChangeEvent event) {
        if (event.deleted()
                || event.type() == ChangeEvent.EventType.DELETE) {
            storage.delete(event.key());
        } else {
            storage.put(event.key(), event.value());
        }
    }

    private record Staged(byte[] key, byte[] value, boolean deleted) {
    }
}
