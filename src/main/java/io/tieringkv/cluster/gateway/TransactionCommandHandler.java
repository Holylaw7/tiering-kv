package io.tieringkv.cluster.gateway;

import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.mvcc.LockConflictException;
import io.tieringkv.mvcc.TransactionAbortedException;
import io.tieringkv.mvcc.WriteConflictException;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 事务命令处理器（ADR-0079）：把 GET/SET/DEL/MGET/MSET 委托给
 * AutoTransactionExecutor，写命令记录 redis_txn_latency，冲突映射为
 * RESP 错误。协议层不变。
 */
public final class TransactionCommandHandler {

    private final AutoTransactionExecutor executor;
    private final GatewayMetricsRegistry metrics;

    public TransactionCommandHandler(AutoTransactionExecutor executor,
                                     GatewayMetricsRegistry metrics) {
        this.executor = executor;
        this.metrics = metrics;
    }

    public RespValue get(byte[] key) {
        byte[] value = executor.get(key);
        return value == null ? RespNull.BULK_STRING : new RespBulkString(value);
    }

    public RespValue set(byte[] key, byte[] value) {
        return transaction(() -> {
            executor.set(key, value);
            return new RespSimpleString("OK");
        });
    }

    public RespValue del(byte[] key) {
        return transaction(() -> new RespInteger(executor.delete(key) ? 1 : 0));
    }

    public RespValue mget(List<byte[]> keys) {
        List<RespValue> values = new ArrayList<>(keys.size());
        for (byte[] value : executor.mget(keys)) {
            values.add(value == null ? RespNull.BULK_STRING
                    : new RespBulkString(value));
        }
        return new RespArray(values);
    }

    public RespValue mset(List<byte[]> pairs) {
        return transaction(() -> {
            executor.mset(pairs);
            return new RespSimpleString("OK");
        });
    }

    /** INFO TRANSACTION / INFO MVCC / INFO Gateway 聚合段。 */
    public String infoSections() {
        StringBuilder builder = new StringBuilder();
        if (executor.metrics() != null) {
            builder.append(executor.metrics().sectionText());
        } else {
            builder.append("# Transaction\r\n"
                    + "begin_txn:0\r\n"
                    + "active_txn:0\r\n"
                    + "committed_txn:0\r\n"
                    + "rollback_txn:0\r\n"
                    + "conflict_txn:0\r\n"
                    + "abort_txn:0\r\n"
                    + "recovery_txn:0\r\n"
                    + "read_txn:0\r\n"
                    + "lock_count:0\r\n"
                    + "txn_commit_latency_ms:0.000\r\n");
        }
        builder.append(executor.mvccInfo());
        builder.append(metrics.sectionText());
        return builder.toString();
    }

    private RespValue transaction(java.util.function.Supplier<RespValue> action) {
        long t0 = System.nanoTime();
        try {
            RespValue result = action.get();
            metrics.recordTransaction(System.nanoTime() - t0);
            return result;
        } catch (WriteConflictException | LockConflictException
                 | TransactionAbortedException e) {
            metrics.recordTransaction(System.nanoTime() - t0);
            return new RespError("ERR transaction conflict: " + e.getMessage());
        } catch (RuntimeException e) {
            metrics.recordTransaction(System.nanoTime() - t0);
            return new RespError("ERR transaction failed: " + e.getMessage());
        }
    }
}
