package io.tieringkv.storage.wal;

/** WAL 记录损坏：magic/version/checksum 校验失败或结构非法。 */
public final class WalCorruptionException extends RuntimeException {

    public WalCorruptionException(String message) {
        super(message);
    }
}
