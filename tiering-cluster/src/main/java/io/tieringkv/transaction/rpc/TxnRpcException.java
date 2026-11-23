package io.tieringkv.transaction.rpc;

/** 分布式事务 RPC 失败（ADR-0083）。 */
public final class TxnRpcException extends RuntimeException {

    public TxnRpcException(String message) {
        super(message);
    }

    public TxnRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
