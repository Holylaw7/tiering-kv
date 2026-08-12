package io.tieringkv.sql.txn;

import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;
import io.tieringkv.transaction.geo.GeoTransactionCoordinator;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 写 2PC 真实协调器端到端（ADR-0144）：WriteOp → GeoTransactionCoordinator
 * 的 begin/prewrite/commit/rollback 全链路，与原生 2PC 语义等价。
 */
public final class SqlTxnCoordinatorAdapter {

    private final GeoTransactionCoordinator coordinator;
    private final CredentialManager credentials;
    private final List<SqlTxnExecutor.WriteOp> pending =
            new ArrayList<>();
    private GeoTransactionCoordinator.GeoTransaction active;
    private boolean inTransaction;
    private String token;

    public SqlTxnCoordinatorAdapter(
            GeoTransactionCoordinator coordinator,
            CredentialManager credentials) {
        this.coordinator = coordinator;
        this.credentials = credentials;
    }

    public void begin(String token) {
        credentials.require(token, Permission.WRITE);
        if (inTransaction) {
            throw new IllegalStateException(
                    "transaction already open");
        }
        this.token = token;
        this.pending.clear();
        this.inTransaction = true;
    }

    public void write(byte[] key, byte[] value, boolean deleted) {
        requireTxn();
        pending.add(new SqlTxnExecutor.WriteOp("coordinator",
                key, value, deleted));
    }

    public boolean commit() {
        requireTxn();
        credentials.require(token, Permission.WRITE);
        List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (SqlTxnExecutor.WriteOp write : pending) {
            mutations.add(new TxnMessages.Mutation(write.key(),
                    write.value(), write.deleted()));
        }
        active = coordinator.begin(mutations);
        try {
            coordinator.commit(active);
            active = null;
            pending.clear();
            inTransaction = false;
            token = null;
            return true;
        } catch (IOException | IllegalStateException e) {
            // prewrite 失败时协调器已回滚；清理本地会话，失败不抛出。
            active = null;
            pending.clear();
            inTransaction = false;
            token = null;
            return false;
        }
    }

    public boolean rollback() {
        requireTxn();
        if (active != null) {
            try {
                coordinator.rollback(active);
            } catch (IOException ignored) {
                // 决策日志已尽力，会话仍清理
            }
            active = null;
        }
        pending.clear();
        inTransaction = false;
        token = null;
        return true;
    }

    /** 恢复：按决策日志重放未完成事务（与原生 2PC 相同）。 */
    public int recover() throws IOException {
        return coordinator.recover();
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
