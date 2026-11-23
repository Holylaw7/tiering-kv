package io.tieringkv.sql.txn;

import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL 写 2PC 生产接线（ADR-0138）：生命周期 + RBAC + 提交。 */
class SqlTxn2PcProductionTest {

    @Test
    void beginRequiresWritePermission() {
        CredentialManager credentials = new CredentialManager();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        assertThatThrownBy(() -> executor.begin(
                credentials.issue(Role.READER, 60_000)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void beginCommitRoundTrip() {
        CredentialManager credentials = new CredentialManager();
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                }, credentials);
        String token = credentials.issue(Role.WRITER, 60_000);
        executor.begin(token);
        executor.write(bytes("k"), bytes("v"), false);
        assertThat(executor.commit()).isTrue();
        assertThat(received).hasSize(1);
        assertThat(executor.inTransaction()).isFalse();
    }

    @Test
    void rollbackDiscards() {
        CredentialManager credentials = new CredentialManager();
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                }, credentials);
        executor.begin(credentials.issue(Role.WRITER, 60_000));
        executor.write(bytes("k"), bytes("v"), false);
        executor.rollback();
        assertThat(received).isEmpty();
        assertThat(executor.pendingCount()).isZero();
    }

    @Test
    void commitFailurePropagates() {
        CredentialManager credentials = new CredentialManager();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> false, credentials);
        executor.begin(credentials.issue(Role.WRITER, 60_000));
        executor.write(bytes("k"), bytes("v"), false);
        assertThat(executor.commit()).isFalse();
        assertThat(executor.inTransaction()).isFalse();
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedWriteVolume(int count) {
        CredentialManager credentials = new CredentialManager();
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                }, credentials);
        executor.begin(credentials.issue(Role.WRITER, 60_000));
        for (int i = 0; i < count; i++) {
            executor.write(bytes("k" + i), bytes("v"), false);
        }
        assertThat(executor.commit()).isTrue();
        assertThat(received).hasSize(count);
    }

    @Test
    void deleteMarkedInMutation() {
        CredentialManager credentials = new CredentialManager();
        List<TxnMessages.Mutation> received = new ArrayList<>();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                }, credentials);
        executor.begin(credentials.issue(Role.WRITER, 60_000));
        executor.write(bytes("k"), null, true);
        executor.commit();
        assertThat(received.get(0).deleted()).isTrue();
        assertThat(received.get(0).value()).isNull();
    }

    @Test
    void writeWithoutBeginRejected() {
        CredentialManager credentials = new CredentialManager();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        assertThatThrownBy(() -> executor.write(
                bytes("k"), bytes("v"), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doubleBeginRejected() {
        CredentialManager credentials = new CredentialManager();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        executor.begin(credentials.issue(Role.WRITER, 60_000));
        assertThatThrownBy(() -> executor.begin(
                credentials.issue(Role.WRITER, 60_000)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitWithoutBeginRejected() {
        CredentialManager credentials = new CredentialManager();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        assertThatThrownBy(executor::commit)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokedTokenCommitRejected() {
        CredentialManager credentials = new CredentialManager();
        SqlTxn2PcExecutor executor = new SqlTxn2PcExecutor(
                mutations -> true, credentials);
        String token = credentials.issue(Role.WRITER, 60_000);
        executor.begin(token);
        credentials.revoke(token);
        assertThatThrownBy(executor::commit)
                .isInstanceOf(SecurityException.class);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
