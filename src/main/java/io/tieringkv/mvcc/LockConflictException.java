package io.tieringkv.mvcc;

/** 锁冲突（ADR-0074）。 */
public final class LockConflictException extends RuntimeException {

    public LockConflictException(String message) {
        super(message);
    }
}
