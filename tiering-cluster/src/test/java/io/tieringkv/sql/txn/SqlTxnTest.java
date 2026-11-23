package io.tieringkv.sql.txn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL 写事务（ADR-0128）：解析、执行、回滚。 */
class SqlTxnTest {

    @Test
    void parseBegin() {
        SqlTxnStatement statement = new SqlTxnParser().parse("BEGIN");
        assertThat(statement.type()).isEqualTo(
                SqlTxnStatement.Type.BEGIN);
    }

    @Test
    void parseSet() {
        SqlTxnStatement statement = new SqlTxnParser()
                .parse("SET 'user:1' = 'v1'");
        assertThat(statement.type()).isEqualTo(
                SqlTxnStatement.Type.SET);
        assertThat(new String(statement.key(),
                StandardCharsets.UTF_8)).isEqualTo("user:1");
        assertThat(new String(statement.value(),
                StandardCharsets.UTF_8)).isEqualTo("v1");
    }

    @Test
    void parseDelete() {
        SqlTxnStatement statement = new SqlTxnParser().parse(
                "DELETE FROM kv WHERE key = 'user:1'");
        assertThat(statement.type()).isEqualTo(
                SqlTxnStatement.Type.DELETE);
    }

    @Test
    void parseCommitAndRollback() {
        assertThat(new SqlTxnParser().parse("COMMIT").type())
                .isEqualTo(SqlTxnStatement.Type.COMMIT);
        assertThat(new SqlTxnParser().parse("ROLLBACK").type())
                .isEqualTo(SqlTxnStatement.Type.ROLLBACK);
    }

    @ParameterizedTest(name = "sql {0}")
    @ValueSource(strings = {"BEGIN", "SET 'a' = 'b'",
            "DELETE FROM kv WHERE key = 'a'", "COMMIT", "ROLLBACK"})
    void parameterizedParse(String sql) {
        assertThat(new SqlTxnParser().parse(sql)).isNotNull();
    }

    @ParameterizedTest(name = "sql {0}")
    @ValueSource(strings = {"SELECT * FROM kv", "SET a = b",
            "DELETE FROM kv", "BEGIN; COMMIT"})
    void parameterizedInvalidParse(String sql) {
        assertThatThrownBy(() -> new SqlTxnParser().parse(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executorCollectsWrites() {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse("SET 'k' = 'v'"));
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(committed).hasSize(1);
        assertThat(committed.get(0).region()).isEqualTo("r1");
        assertThat(executor.inTransaction()).isFalse();
    }

    @Test
    void executorRoutesByRegion() {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> new String(key, StandardCharsets.UTF_8)
                        .startsWith("b") ? "r2" : "r1",
                committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse("SET 'b1' = 'v'"));
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(committed.get(0).region()).isEqualTo("r2");
    }

    @Test
    void executorRollbackDiscards() {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse("SET 'k' = 'v'"));
        executor.execute(new SqlTxnParser().parse("ROLLBACK"));
        assertThat(committed).isEmpty();
        assertThat(executor.pendingCount()).isZero();
    }

    @Test
    void executorWriteWithoutBeginRejected() {
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", ops -> {
                });
        assertThatThrownBy(() -> executor.execute(
                new SqlTxnParser().parse("SET 'k' = 'v'")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void executorDoubleBeginRejected() {
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", ops -> {
                });
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        assertThatThrownBy(() -> executor.execute(
                new SqlTxnParser().parse("BEGIN")))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedWriteVolume(int count) {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        for (int i = 0; i < count; i++) {
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v" + i + "'"));
        }
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(committed).hasSize(count);
    }

    @Test
    void executorDeleteMarked() {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse(
                "DELETE FROM kv WHERE key = 'k'"));
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(committed.get(0).deleted()).isTrue();
        assertThat(committed.get(0).value()).isNull();
    }
}
