package io.tieringkv.mvcc;

import java.util.concurrent.atomic.AtomicLong;

/** 单 Region 事务管理器（ADR-0073）：begin/commit/rollback。 */
public final class TransactionManager {

    private final TimestampOracle oracle;
    private final MvccStorageEngine engine;
    private final LockTable locks;
    private final long lockTtlMillis;
    private final AtomicLong txnIds = new AtomicLong();

    public TransactionManager(TimestampOracle oracle,
                              MvccStorageEngine engine,
                              LockTable locks,
                              long lockTtlMillis) {
        this.oracle = oracle;
        this.engine = engine;
        this.locks = locks;
        this.lockTtlMillis = lockTtlMillis;
    }

    public Transaction begin() {
        return new Transaction("txn-" + txnIds.incrementAndGet(),
                oracle.nextTimestamp());
    }

    public Transaction beginAt(long startTS) {
        return new Transaction("txn-" + txnIds.incrementAndGet(), startTS);
    }

    public void commit(Transaction txn) {
        txn.commit(engine, locks, oracle, lockTtlMillis);
    }

    public void rollback(Transaction txn) {
        txn.rollback(engine, locks);
    }

    public MvccStorageEngine engine() {
        return engine;
    }

    public LockTable locks() {
        return locks;
    }

    public TimestampOracle oracle() {
        return oracle;
    }
}
