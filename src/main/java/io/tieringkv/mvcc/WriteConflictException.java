package io.tieringkv.mvcc;

/** 写写/读写冲突（ADR-0074）。 */
public final class WriteConflictException extends RuntimeException {

    public WriteConflictException(String message) {
        super(message);
    }
}
