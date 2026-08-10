package io.tieringkv.storage.tiering;

/** 背压拒绝（ADR-0021）：CRITICAL 且等待超时，写路径返回 -ERR。 */
public final class BackpressureException extends RuntimeException {

    public BackpressureException(String message) {
        super(message);
    }
}
