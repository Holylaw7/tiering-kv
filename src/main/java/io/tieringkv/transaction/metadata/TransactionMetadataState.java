package io.tieringkv.transaction.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 全局事务状态（ADR-0084）：应用 Raft 命令，支持恢复查询。 */
public final class TransactionMetadataState {

    private final Map<String, TxnMetaEntry> entries = new ConcurrentHashMap<>();

    public void apply(TxnMetaCommand command) {
        switch (command.type()) {
            case REGISTER -> entries.put(command.txnId(),
                    new TxnMetaEntry(command.txnId(), command.primary(),
                            command.startTS(), 0, command.decisionIndex(),
                            TxnMetaEntry.State.REGISTERED,
                            command.regionMutations()));
            case PREPARE -> update(command.txnId(), entry -> new TxnMetaEntry(
                    entry.txnId(), entry.primary(), entry.startTS(),
                    command.commitTS(), command.decisionIndex(),
                    TxnMetaEntry.State.PREPARED,
                    entry.regionMutations()));
            case COMMIT -> update(command.txnId(), entry -> new TxnMetaEntry(
                    entry.txnId(), entry.primary(), entry.startTS(),
                    command.commitTS(), command.decisionIndex(),
                    TxnMetaEntry.State.COMMITTED,
                    entry.regionMutations()));
            case ROLLBACK -> update(command.txnId(), entry -> new TxnMetaEntry(
                    entry.txnId(), entry.primary(), entry.startTS(),
                    entry.commitTS(), command.decisionIndex(),
                    TxnMetaEntry.State.ROLLED_BACK,
                    entry.regionMutations()));
        }
    }

    public TxnMetaEntry get(String txnId) {
        return entries.get(txnId);
    }

    public Map<String, TxnMetaEntry> snapshot() {
        return Map.copyOf(entries);
    }

    /** 待恢复事务（未终态）。 */
    public List<TxnMetaEntry> pending() {
        List<TxnMetaEntry> pending = new ArrayList<>();
        for (TxnMetaEntry entry : entries.values()) {
            if (!entry.terminal()) {
                pending.add(entry);
            }
        }
        return pending;
    }

    public int size() {
        return entries.size();
    }

    private void update(String txnId,
                        java.util.function.UnaryOperator<TxnMetaEntry> updater) {
        entries.computeIfPresent(txnId, (id, entry) -> updater.apply(entry));
    }
}
