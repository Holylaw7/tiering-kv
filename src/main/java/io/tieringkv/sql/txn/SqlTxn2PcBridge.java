package io.tieringkv.sql.txn;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** SQL 写 2PC 桥接（ADR-0133）：WriteOp → Mutation → 2PC 提交。 */
public final class SqlTxn2PcBridge {

    private final Function<List<TxnMessages.Mutation>, Boolean>
            commit2pc;

    public SqlTxn2PcBridge(
            Function<List<TxnMessages.Mutation>, Boolean> commit2pc) {
        this.commit2pc = commit2pc;
    }

    public boolean commit(List<SqlTxnExecutor.WriteOp> writes) {
        List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (SqlTxnExecutor.WriteOp write : writes) {
            mutations.add(new TxnMessages.Mutation(write.key(),
                    write.value(), write.deleted()));
        }
        return commit2pc.apply(mutations);
    }
}
