package io.tieringkv.sql.txn;

import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 写 2PC 桥接（ADR-0133）：WriteOp → Mutation → 提交。 */
class SqlTxn2PcTest {

    @Test
    void bridgeConvertsWritesToMutations() {
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                });
        boolean ok = bridge.commit(List.of(
                new SqlTxnExecutor.WriteOp("r1", bytes("k"),
                        bytes("v"), false)));
        assertThat(ok).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).key()).isEqualTo(bytes("k"));
        assertThat(received.get(0).value()).isEqualTo(bytes("v"));
        assertThat(received.get(0).deleted()).isFalse();
    }

    @Test
    void bridgePreservesDelete() {
        AtomicBoolean committed = new AtomicBoolean();
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> {
                    assertThat(mutations.get(0).deleted()).isTrue();
                    assertThat(mutations.get(0).value()).isNull();
                    committed.set(true);
                    return true;
                });
        bridge.commit(List.of(new SqlTxnExecutor.WriteOp(
                "r1", bytes("k"), null, true)));
        assertThat(committed.get()).isTrue();
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedBridgeVolume(int count) {
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                });
        List<SqlTxnExecutor.WriteOp> writes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            writes.add(new SqlTxnExecutor.WriteOp("r" + (i % 2),
                    bytes("k" + i), bytes("v"), false));
        }
        assertThat(bridge.commit(writes)).isTrue();
        assertThat(received).hasSize(count);
    }

    @Test
    void bridgeFailurePropagates() {
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> false);
        assertThat(bridge.commit(List.of(
                new SqlTxnExecutor.WriteOp("r1", bytes("k"),
                        bytes("v"), false)))).isFalse();
    }

    @Test
    void emptyWritesCommitNoop() {
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> mutations.isEmpty());
        assertThat(bridge.commit(List.of())).isTrue();
    }

    @Test
    void endToEndSqlTo2PC() {
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                });
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", writes -> bridge.commit(writes));
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse("SET 'k' = 'v'"));
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(received).hasSize(1);
        assertThat(received.get(0).key()).isEqualTo(bytes("k"));
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {1, 20})
    void endToEndVolume(int count) {
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                });
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", writes -> bridge.commit(writes));
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        for (int i = 0; i < count; i++) {
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v'"));
        }
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(received).hasSize(count);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
