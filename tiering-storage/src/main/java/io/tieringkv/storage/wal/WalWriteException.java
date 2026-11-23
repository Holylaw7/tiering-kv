package io.tieringkv.storage.wal;

/** WAL 写入失败（IO 错误等）；写路径不得谎报成功。 */
public final class WalWriteException extends RuntimeException {

    public WalWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
