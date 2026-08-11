package io.tieringkv.sql.txn;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** SQL 写事务执行器（ADR-0128）：收集变更 → 提交回调（2PC 由调用方接）。 */
public final class SqlTxnExecutor {

    private final Function<byte[], String> regionRouter;
    private final Consumer<List<WriteOp>> commitSink;
    private final List<WriteOp> pending = new ArrayList<>();
    private boolean inTransaction;

    public record WriteOp(String region, byte[] key, byte[] value,
                          boolean deleted) {
    }

    public SqlTxnExecutor(Function<byte[], String> regionRouter,
                          Consumer<List<WriteOp>> commitSink) {
        this.regionRouter = regionRouter;
        this.commitSink = commitSink;
    }

    public void execute(SqlTxnStatement statement) {
        switch (statement.type()) {
            case BEGIN -> {
                if (inTransaction) {
                    throw new IllegalStateException(
                            "transaction already open");
                }
                inTransaction = true;
                pending.clear();
            }
            case SET, DELETE -> {
                requireTxn();
                pending.add(new WriteOp(
                        regionRouter.apply(statement.key()),
                        statement.key(), statement.value(),
                        statement.type() == SqlTxnStatement.Type.DELETE));
            }
            case COMMIT -> {
                requireTxn();
                commitSink.accept(List.copyOf(pending));
                pending.clear();
                inTransaction = false;
            }
            case ROLLBACK -> {
                requireTxn();
                pending.clear();
                inTransaction = false;
            }
        }
    }

    public boolean inTransaction() {
        return inTransaction;
    }

    public int pendingCount() {
        return pending.size();
    }

    private void requireTxn() {
        if (!inTransaction) {
            throw new IllegalStateException(
                    "no active transaction");
        }
    }
}
