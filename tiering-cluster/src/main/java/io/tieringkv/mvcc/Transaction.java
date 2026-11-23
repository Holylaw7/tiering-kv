package io.tieringkv.mvcc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 事务（ADR-0073）：ACTIVE→PREWRITING→PREPARED→COMMITTED/ROLLED_BACK/ABORTED。 */
public final class Transaction {

    public enum State {
        ACTIVE,
        PREWRITING,
        PREPARED,
        COMMITTED,
        ROLLED_BACK,
        ABORTED
    }

    private final String txnId;
    private final long startTS;
    private long commitTS = -1;
    private State state = State.ACTIVE;
    private byte[] primaryKey;
    private final Map<ByteKey, byte[]> writes = new LinkedHashMap<>();
    private final Set<ByteKey> deletes = ConcurrentHashMap.newKeySet();
    private final Set<ByteKey> readSet = ConcurrentHashMap.newKeySet();
    private final List<byte[]> lockedKeys = new ArrayList<>();
    private final SnapshotReader reader = new SnapshotReader();

    public Transaction(String txnId, long startTS) {
        this.txnId = txnId;
        this.startTS = startTS;
    }

    public String txnId() {
        return txnId;
    }

    public long startTS() {
        return startTS;
    }

    public long commitTS() {
        return commitTS;
    }

    public synchronized State state() {
        return state;
    }

    public byte[] primaryKey() {
        return primaryKey == null ? null : primaryKey.clone();
    }

    public byte[] primaryKeyOr(byte[] fallback) {
        return primaryKey == null ? fallback : primaryKey;
    }

    public byte[] get(MvccStorageEngine engine, byte[] key) {
        readSet.add(new ByteKey(key));
        return reader.get(engine, key, startTS);
    }

    public void put(byte[] key, byte[] value) {
        writes.put(new ByteKey(key), value.clone());
        deletes.remove(new ByteKey(key));
    }

    public void delete(byte[] key) {
        deletes.add(new ByteKey(key));
        writes.remove(new ByteKey(key));
    }

    public int writeCount() {
        return writes.size() + deletes.size();
    }

    public Set<ByteKey> readSet() {
        return Set.copyOf(readSet);
    }

    public Set<ByteKey> writeKeys() {
        return Set.copyOf(writes.keySet());
    }

    public Set<ByteKey> deleteKeys() {
        return Set.copyOf(deletes);
    }

    public byte[] writeValue(ByteKey key) {
        return writes.get(key);
    }

    public synchronized List<byte[]> lockedKeys() {
        return List.copyOf(lockedKeys);
    }

    public synchronized void markPrepared(long commitTS) {
        this.commitTS = commitTS;
        this.state = State.PREPARED;
    }

    public synchronized void markCommitted(long commitTS) {
        this.commitTS = commitTS;
        this.state = State.COMMITTED;
    }

    public synchronized void markRolledBack() {
        this.state = State.ROLLED_BACK;
    }

    /** 单 Region 提交（Percolator 2PC 本地等价路径）。 */
    public synchronized void commit(MvccStorageEngine engine, LockTable locks,
                                    TimestampOracle oracle, long lockTtlMillis) {
        if (state != State.ACTIVE) {
            throw new TransactionAbortedException("transaction not active: " + state);
        }
        state = State.PREWRITING;
        primaryKey = firstWriteKey();
        PrewriteExecutor prewrite = new PrewriteExecutor();
        try {
            for (ByteKey key : writes.keySet()) {
                prewrite.prewrite(engine, locks, key.key(), writes.get(key),
                        false, txnId, primaryKey, startTS, lockTtlMillis,
                        System.currentTimeMillis(), readSet);
                lockedKeys.add(key.key());
            }
            for (ByteKey key : deletes) {
                prewrite.prewrite(engine, locks, key.key(), null,
                        true, txnId, primaryKey, startTS, lockTtlMillis,
                        System.currentTimeMillis(), readSet);
                lockedKeys.add(key.key());
            }
            state = State.PREPARED;
            commitTS = oracle.nextTimestamp();
            CommitExecutor commit = new CommitExecutor();
            for (ByteKey key : writes.keySet()) {
                commit.commit(engine, locks, key.key(), writes.get(key),
                        false, txnId, startTS, commitTS);
            }
            for (ByteKey key : deletes) {
                commit.commit(engine, locks, key.key(), null,
                        true, txnId, startTS, commitTS);
            }
            state = State.COMMITTED;
        } catch (RuntimeException e) {
            rollback(engine, locks);
            throw e;
        }
    }

    public synchronized void rollback(MvccStorageEngine engine, LockTable locks) {
        if (state == State.COMMITTED) {
            return;
        }
        RollbackExecutor rollback = new RollbackExecutor();
        for (byte[] key : lockedKeys) {
            rollback.rollback(engine, locks, key, txnId, startTS);
        }
        lockedKeys.clear();
        state = state == State.PREPARED || state == State.PREWRITING
                ? State.ROLLED_BACK : State.ABORTED;
    }

    public synchronized void abort() {
        state = State.ABORTED;
    }

    private byte[] firstWriteKey() {
        if (!writes.isEmpty()) {
            return writes.keySet().iterator().next().key();
        }
        return deletes.isEmpty() ? new byte[]{0}
                : deletes.iterator().next().key();
    }
}
