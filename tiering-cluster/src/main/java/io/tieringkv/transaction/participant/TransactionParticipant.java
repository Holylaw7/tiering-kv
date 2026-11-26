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

    @FunctionalInterface
    public interface CheckpointAction {
        int execute(Map<byte[], byte[]> snapshot) throws java.io.IOException;
    }

    private final String regionId;
    private final MvccStorageEngine engine;
    private final LockTable locks;
    private final long lockTtlMillis;
    private final PersistentTxnJournal journal;
    private final CheckpointAction checkpointAction;
    private final Map<String, TxnMessages.ParticipantState> states =
            new ConcurrentHashMap<>();
    private final Map<String, Object> txnLocks = new ConcurrentHashMap<>();

    public TransactionParticipant(String regionId, MvccStorageEngine engine,
                                  LockTable locks, long lockTtlMillis) {
        this(regionId, engine, locks, lockTtlMillis, null, null);
    }

    public TransactionParticipant(String regionId, MvccStorageEngine engine,
                                  LockTable locks, long lockTtlMillis,
                                  PersistentTxnJournal journal) {
        this(regionId, engine, locks, lockTtlMillis, journal, null);
    }

    public TransactionParticipant(String regionId, MvccStorageEngine engine,
                                  LockTable locks, long lockTtlMillis,
                                  PersistentTxnJournal journal,
                                  CheckpointAction checkpointAction) {
        this.regionId = regionId;
        this.engine = engine;
        this.locks = locks;
        this.lockTtlMillis = lockTtlMillis;
        this.journal = journal;
        this.checkpointAction = checkpointAction;
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
        synchronized (txnLock(request.txnId())) {
            return prewriteLocked(request);
        }
    }

    private TxnMessages.Response prewriteLocked(
            TxnMessages.Prewrite request) {
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
        synchronized (txnLock(request.txnId())) {
            return commitLocked(request);
        }
    }

    private TxnMessages.Response commitLocked(TxnMessages.Commit request) {
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
        synchronized (txnLock(request.txnId())) {
            return rollbackLocked(request);
        }
    }

    private TxnMessages.Response rollbackLocked(
            TxnMessages.Rollback request) {
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

    /** CHECK_TXN_STATUS（ADR-0092）：返回本 participant 记录的事务状态。 */
    public TxnMessages.Response checkStatus(String txnId) {
        TxnMessages.ParticipantState state = states.get(txnId);
        return state == null
                ? TxnMessages.Response.error("UNKNOWN")
                : new TxnMessages.Response(TxnMessages.Status.OK,
                state.name());
    }

    /** RESOLVE_LOCK（ADR-0092）：按本地状态补完 commit 或回滚（幂等）。 */
    public TxnMessages.Response resolveLock(TxnMessages.Commit commit,
                                            boolean rollbackDecision) {
        if (rollbackDecision) {
            return rollback(new TxnMessages.Rollback(commit.txnId(),
                    commit.startTS(), commit.primary()));
        }
        return commit(commit);
    }

    /** TXN_GET：读最新已提交值（运行时网关读取路径）。 */
    public TxnMessages.Response get(byte[] key) {
        byte[] value = engine.latestValue(key);
        return value == null
                ? new TxnMessages.Response(TxnMessages.Status.ALREADY, "")
                : new TxnMessages.Response(TxnMessages.Status.OK,
                new String(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** TXN_SCAN：返回本 Region 全部已提交存活键值（供网关 SCAN / DBSIZE 汇聚）。 */
    public List<TxnMessages.ScanEntry> scan() {
        Map<byte[], byte[]> live = engine.scan(null, null, Long.MAX_VALUE);
        List<TxnMessages.ScanEntry> entries = new ArrayList<>(live.size());
        for (Map.Entry<byte[], byte[]> entry : live.entrySet()) {
            entries.add(new TxnMessages.ScanEntry(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    /** TXN_CHECKPOINT：将本 Region 当前已提交快照原子落盘（供网关 BGSAVE 协调）。 */
    public TxnMessages.Response checkpoint() {
        Map<byte[], byte[]> live = engine.scan(null, null, Long.MAX_VALUE);
        if (checkpointAction == null) {
            return new TxnMessages.Response(TxnMessages.Status.OK,
                    "entries=" + live.size());
        }
        try {
            int written = checkpointAction.execute(live);
            return new TxnMessages.Response(TxnMessages.Status.OK,
                    "entries=" + written);
        } catch (java.io.IOException | RuntimeException ex) {
            String msg = ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage();
            return TxnMessages.Response.error("checkpoint failed: " + msg);
        }
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

    private Object txnLock(String txnId) {
        return txnLocks.computeIfAbsent(txnId, ignored -> new Object());
    }
}
