package io.tieringkv.sql.txn;

import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** SQL 写 2PC 生产执行（ADR-0138）：BEGIN → 写 → 真实 2PC 提交。 */
public final class SqlTxn2PcExecutor {

    private final Function<List<TxnMessages.Mutation>, Boolean>
            commit2pc;
    private final CredentialManager credentials;
    private final List<SqlTxnExecutor.WriteOp> pending =
            new ArrayList<>();
    private boolean inTransaction;
    private String token;

    public SqlTxn2PcExecutor(
            Function<List<TxnMessages.Mutation>, Boolean> commit2pc,
            CredentialManager credentials) {
        this.commit2pc = commit2pc;
        this.credentials = credentials;
    }

    public void begin(String token) {
        credentials.require(token, Permission.WRITE);
        if (inTransaction) {
            throw new IllegalStateException(
                    "transaction already open");
        }
        this.token = token;
        this.inTransaction = true;
        this.pending.clear();
    }

    public void write(byte[] key, byte[] value, boolean deleted) {
        requireTxn();
        pending.add(new SqlTxnExecutor.WriteOp("r1", key, value,
                deleted));
    }

    public boolean commit() {
        requireTxn();
        credentials.require(token, Permission.WRITE);
        List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (SqlTxnExecutor.WriteOp write : pending) {
            mutations.add(new TxnMessages.Mutation(write.key(),
                    write.value(), write.deleted()));
        }
        boolean ok = commit2pc.apply(mutations);
        pending.clear();
        inTransaction = false;
        token = null;
        return ok;
    }

    public void rollback() {
        requireTxn();
        pending.clear();
        inTransaction = false;
        token = null;
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
