package io.tieringkv.platform;

import io.tieringkv.sql.txn.SqlTxnExecutor;
import io.tieringkv.sql.txn.SqlTxnParser;
import io.tieringkv.sql.txn.SqlTxnStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 30 SQL 写事务边缘：解析/执行参数矩阵。 */
class Phase30SqlTxnEdgeTest {

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"a", "user:1", "中文"})
    void setKeyBoundaries(String key) {
        SqlTxnStatement statement = new SqlTxnParser().parse(
                "SET '" + key + "' = 'v'");
        assertThat(new String(statement.key(),
                StandardCharsets.UTF_8)).isEqualTo(key);
    }

    @Test
    void setLongKeyBoundary() {
        String key = "k".repeat(32);
        SqlTxnStatement statement = new SqlTxnParser().parse(
                "SET '" + key + "' = 'v'");
        assertThat(new String(statement.key(),
                StandardCharsets.UTF_8)).isEqualTo(key);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(strings = {"", "v", "hello world", "中文"})
    void setValueBoundaries(String value) {
        SqlTxnStatement statement = new SqlTxnParser().parse(
                "SET 'k' = '" + value + "'");
        assertThat(new String(statement.value(),
                StandardCharsets.UTF_8)).isEqualTo(value);
    }

    @ParameterizedTest(name = "sql {0}")
    @ValueSource(strings = {"BEGIN", "COMMIT", "ROLLBACK",
            "SET 'a' = 'b'", "DELETE FROM kv WHERE key = 'a'"})
    void statementRoundTrip(String sql) {
        SqlTxnStatement statement = new SqlTxnParser().parse(sql);
        assertThat(statement.type()).isNotNull();
    }

    @Test
    void caseInsensitiveKeywords() {
        assertThat(new SqlTxnParser().parse("begin").type())
                .isEqualTo(SqlTxnStatement.Type.BEGIN);
        assertThat(new SqlTxnParser().parse("Set 'k' = 'v'").type())
                .isEqualTo(SqlTxnStatement.Type.SET);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedTxnCycles(int count) {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", committed::addAll);
        for (int i = 0; i < count; i++) {
            executor.execute(new SqlTxnParser().parse("BEGIN"));
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v'"));
            executor.execute(new SqlTxnParser().parse("COMMIT"));
        }
        assertThat(committed).hasSize(count);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1, 25})
    void multiWriteTxn(int ops) {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r2", committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        for (int i = 0; i < ops; i++) {
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v'"));
        }
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(committed).hasSize(ops);
        assertThat(committed.get(0).region()).isEqualTo("r2");
    }

    @Test
    void rollbackAfterCommitRejected() {
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", ops -> {
                });
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThatThrownBy(() -> executor.execute(
                new SqlTxnParser().parse("ROLLBACK")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitWithoutBeginRejected() {
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", ops -> {
                });
        assertThatThrownBy(() -> executor.execute(
                new SqlTxnParser().parse("COMMIT")))
                .isInstanceOf(IllegalStateException.class);
    }
}
