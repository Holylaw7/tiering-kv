package io.tieringkv.transaction.participant;

import io.tieringkv.mvcc.CommitExecutor;
import io.tieringkv.mvcc.LockRecord;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.PersistentTxnJournal;
import io.tieringkv.mvcc.PrewriteExecutor;
import io.tieringkv.mvcc.RollbackExecutor;
import io.tieringkv.mvcc.TxnStateRecord;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region 事务参与者（ADR-0083）：状态机 LOCKED → PREPARED → COMMITTED /
 * ROLLED_BACK；全部 RPC 幂等（重试/恢复安全）。
 */
public final class TransactionParticipant {

    private final String regionId;
    private final MvccStorageEngine engine;
    private final LockTable locks;
    private final long lockTtlMillis;
    private final PersistentTxnJournal journal;
    private final Map<String, TxnMessages.ParticipantState> states =
            new ConcurrentHashMap<>();

    public TransactionParticipant(String regionId, MvccStorageEngine engine,
                                  LockTable locks, long lockTtlMillis) {
        this(regionId, engine, locks, lockTtlMillis, null);
    }

    public TransactionParticipant(String regionId, MvccStorageEngine engine,
                                  LockTable locks, long lockTtlMillis,
                                  PersistentTxnJournal journal) {
        this.regionId = regionId;
        this.engine = engine;
        this.locks = locks;
        this.lockTtlMillis = lockTtlMillis;
        this.journal = journal;
    }

    public String regionId() {
        return regionId;
    }

    public MvccStorageEngine engine() {
        return engine;
    }

    public LockTable locks() {
        return locks;
    }

    public TxnMessages.ParticipantState state(String txnId) {
        return states.getOrDefault(txnId, null);
    }

    /** PREWRITE：写锁 + provisional；重复/已完成幂等。 */
    public TxnMessages.Response prewrite(TxnMessages.Prewrite request) {
        TxnMessages.ParticipantState current = states.get(request.txnId());
        if (current == TxnMessages.ParticipantState.COMMITTED
                || current == TxnMessages.ParticipantState.ROLLED_BACK) {
            return TxnMessages.Response.already();
        }
        try {
            PrewriteExecutor prewrite = new PrewriteExecutor();
            for (TxnMessages.Mutation mutation : request.mutations()) {
                LockRecord existing = locks.check(mutation.key());
                if (existing != null
                        && existing.txnId().equals(request.txnId())) {
                    continue; // 部分 prewrite 后重试：跳过已锁定 key
                }
                prewrite.prewrite(engine, locks, mutation.key(),
                        mutation.value(), mutation.deleted(), request.txnId(),
                        request.primary(), request.startTS(), lockTtlMillis,
                        System.currentTimeMillis(), java.util.Set.of());
            }
            states.put(request.txnId(), TxnMessages.ParticipantState.LOCKED);
            journal(TxnStateRecord.State.PREWRITE, request.txnId(),
                    request.startTS(), 0, request.primary(),
                    request.mutations());
            return TxnMessages.Response.ok();
        } catch (RuntimeException e) {
            return TxnMessages.Response.conflict(e.getMessage());
        }
    }

    /** COMMIT：写 WriteRecord + 释放锁；无锁但已提交 → 幂等成功。 */
    public TxnMessages.Response commit(TxnMessages.Commit request) {
        if (states.get(request.txnId())
                == TxnMessages.ParticipantState.COMMITTED) {
            return TxnMessages.Response.already();
        }
        if (states.get(request.txnId())
                == TxnMessages.ParticipantState.ROLLED_BACK) {
            return TxnMessages.Response.conflict("rolled back");
        }
        try {
            boolean any = false;
            CommitExecutor commit = new CommitExecutor();
            for (TxnMessages.Mutation mutation : request.mutations()) {
                LockRecord lock = locks.check(mutation.key());
                if (lock == null || !lock.txnId().equals(request.txnId())) {
                    continue; // 已提交/未 prewrite：幂等跳过
                }
                commit.commit(engine, locks, mutation.key(),
                        mutation.value(), mutation.deleted(), request.txnId(),
                        request.startTS(), request.commitTS());
                any = true;
            }
            states.put(request.txnId(), TxnMessages.ParticipantState.COMMITTED);
            journal(TxnStateRecord.State.COMMIT, request.txnId(),
                    request.startTS(), request.commitTS(), request.primary(),
                    request.mutations());
            return any ? TxnMessages.Response.ok()
                    : TxnMessages.Response.already();
        } catch (RuntimeException e) {
            return TxnMessages.Response.error(e.getMessage());
        }
    }

    /** ROLLBACK：清理锁与 provisional；幂等。 */
    public TxnMessages.Response rollback(TxnMessages.Rollback request) {
        TxnMessages.ParticipantState current = states.get(request.txnId());
        if (current == TxnMessages.ParticipantState.COMMITTED
                || current == TxnMessages.ParticipantState.ROLLED_BACK) {
            return TxnMessages.Response.already();
        }
        try {
            RollbackExecutor rollback = new RollbackExecutor();
            for (Map.Entry<io.tieringkv.mvcc.ByteKey, LockRecord> entry
                    : locks.snapshot().entrySet()) {
                LockRecord lock = entry.getValue();
                if (lock.txnId().equals(request.txnId())) {
                    rollback.rollback(engine, locks, lock.key(),
                            request.txnId(), lock.startTS());
                }
            }
            states.put(request.txnId(),
                    TxnMessages.ParticipantState.ROLLED_BACK);
            journal(TxnStateRecord.State.ROLLBACK, request.txnId(),
                    request.startTS(), 0, request.primary(), List.of());
            return TxnMessages.Response.ok();
        } catch (RuntimeException e) {
            return TxnMessages.Response.error(e.getMessage());
        }
    }

    /** HEARTBEAT：刷新锁 TTL，防止超时回滚误杀活跃事务。 */
    public TxnMessages.Response heartbeat(TxnMessages.Heartbeat request) {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Map.Entry<io.tieringkv.mvcc.ByteKey, LockRecord> entry
                : locks.snapshot().entrySet()) {
            LockRecord lock = entry.getValue();
            if (lock.txnId().equals(request.txnId())) {
                locks.refresh(lock.key(), request.txnId(), now,
                        request.ttlMillis());
                any = true;
            }
        }
        return any ? TxnMessages.Response.ok()
                : TxnMessages.Response.already();
    }

    private void journal(TxnStateRecord.State state, String txnId,
                         long startTS, long commitTS, byte[] primary,
                         List<TxnMessages.Mutation> mutations) {
        if (journal == null) {
            return;
        }
        List<TxnStateRecord.Mutation> converted = new ArrayList<>();
        for (TxnMessages.Mutation mutation : mutations) {
            converted.add(new TxnStateRecord.Mutation(mutation.key(),
                    mutation.value(), mutation.deleted()));
        }
        journal.recordState(new TxnStateRecord(txnId, state, startTS,
                commitTS, primary, converted))
                .exceptionally(error -> null).join();
    }
}
