package io.tieringkv.mvcc;

/** 事务中止（ADR-0073）。 */
public final class TransactionAbortedException extends RuntimeException {

    public TransactionAbortedException(String message) {
        super(message);
    }
}
