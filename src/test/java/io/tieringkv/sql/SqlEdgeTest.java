package io.tieringkv.sql;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL 子集边缘（ADR-0113）：解析边界与执行语义。 */
class SqlEdgeTest {

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"", "a", "key with spaces", "中文"})
    void quotedKeyBoundaries(String key) {
        SelectStatement statement = new SqlParser().parse(
                "SELECT * FROM kv WHERE key = '" + key + "'");
        assertThat(new String(statement.exactKey(),
                StandardCharsets.UTF_8)).isEqualTo(key);
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {0, 1, 100})
    void limitBoundaries(int limit) {
        SelectStatement statement = new SqlParser().parse(
                "SELECT * FROM kv LIMIT " + limit);
        assertThat(statement.limit()).isEqualTo(limit);
    }

    @Test
    void negativeLimitRejected() {
        assertThatThrownBy(() -> new SqlParser()
                .parse("SELECT * FROM kv LIMIT -1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactWithRangeRejected() {
        assertThatThrownBy(() -> new SelectStatement(bytes("a"),
                bytes("b"), bytes("c"), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedClauseRejected() {
        assertThatThrownBy(() -> new SqlParser()
                .parse("SELECT * FROM kv ORDER BY key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyStatementRejected() {
        assertThatThrownBy(() -> new SqlParser().parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {2, 20, 100})
    void parameterizedRangeLimit(int count) {
        MvccStorageEngine engine = engine();
        for (int i = 0; i < count; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse(
                        "SELECT * FROM kv WHERE key >= 'k0' LIMIT 5"),
                engine, Long.MAX_VALUE);
        assertThat(rows).hasSize(Math.min(5, count));
    }

    @Test
    void zeroLimitReturnsEmpty() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse("SELECT * FROM kv LIMIT 0"),
                engine, Long.MAX_VALUE);
        assertThat(rows).isEmpty();
    }

    @Test
    void readTsZeroSeesNothing() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse("SELECT * FROM kv"),
                engine, 0);
        assertThat(rows).isEmpty();
    }

    @Test
    void caseInsensitiveSelect() {
        SelectStatement statement = new SqlParser()
                .parse("SeLeCt * FrOm Kv WhErE kEy = 'x'");
        assertThat(statement.exactKey()).isEqualTo(bytes("x"));
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"k0", "k9", "k50"})
    void executeMixedKeys(String key) {
        MvccStorageEngine engine = engine();
        for (int i = 0; i < 10; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse(
                        "SELECT * FROM kv WHERE key = '" + key + "'"),
                engine, Long.MAX_VALUE);
        if (key.equals("k50")) {
            assertThat(rows).isEmpty();
        } else {
            assertThat(rows).hasSize(1);
        }
    }

    private static MvccStorageEngine engine() {
        return new MvccStorageEngine(MemTable.create());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
