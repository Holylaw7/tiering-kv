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

/** SQL 子集（ADR-0113）：解析与 MVCC 快照执行。 */
class SqlSubsetTest {

    @Test
    void parseExactKey() {
        SelectStatement statement = new SqlParser()
                .parse("SELECT * FROM kv WHERE key = 'user:1'");
        assertThat(new String(statement.exactKey(),
                StandardCharsets.UTF_8)).isEqualTo("user:1");
        assertThat(statement.limit()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void parseRange() {
        SelectStatement statement = new SqlParser()
                .parse("SELECT * FROM kv WHERE key >= 'a' AND key < 'b'");
        assertThat(new String(statement.startKey(),
                StandardCharsets.UTF_8)).isEqualTo("a");
        assertThat(new String(statement.endKey(),
                StandardCharsets.UTF_8)).isEqualTo("b");
    }

    @Test
    void parseLimit() {
        SelectStatement statement = new SqlParser()
                .parse("SELECT * FROM kv LIMIT 10");
        assertThat(statement.limit()).isEqualTo(10);
    }

    @Test
    void parseAll() {
        SelectStatement statement = new SqlParser().parse(
                "SELECT * FROM kv WHERE key >= 'a' AND key < 'z' LIMIT 5");
        assertThat(statement.limit()).isEqualTo(5);
        assertThat(statement.startKey()).isEqualTo(bytes("a"));
        assertThat(statement.endKey()).isEqualTo(bytes("z"));
    }

    @Test
    void parseFullScan() {
        SelectStatement statement = new SqlParser()
                .parse("SELECT * FROM kv");
        assertThat(statement.exactKey()).isNull();
        assertThat(statement.startKey()).isNull();
        assertThat(statement.limit()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "sql {0}")
    @ValueSource(strings = {
            "SELECT * FROM kv WHERE key = 'a'",
            "SELECT * FROM kv WHERE key >= 'a' AND key < 'b'",
            "SELECT * FROM kv LIMIT 3",
            "select * from kv where key = 'x' limit 7"
    })
    void parameterizedParse(String sql) {
        assertThat(new SqlParser().parse(sql)).isNotNull();
    }

    @ParameterizedTest(name = "sql {0}")
    @ValueSource(strings = {
            "DELETE FROM kv",
            "SELECT key FROM kv",
            "SELECT * FROM kv WHERE key > 'a'",
            "SELECT * FROM kv LIMIT abc",
            "SELECT * FROM kv WHERE key = a",
            "SELECT * FROM kv WHERE key = 'unterminated"
    })
    void parameterizedInvalidParse(String sql) {
        assertThatThrownBy(() -> new SqlParser().parse(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeExactKey() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("a1"), bytes("v1"), 1, 10, WriteType.PUT);
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse(
                        "SELECT * FROM kv WHERE key = 'a1'"),
                engine, Long.MAX_VALUE);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value()).isEqualTo(bytes("v1"));
    }

    @Test
    void executeMissingKeyEmpty() {
        MvccStorageEngine engine = engine();
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse(
                        "SELECT * FROM kv WHERE key = 'missing'"),
                engine, Long.MAX_VALUE);
        assertThat(rows).isEmpty();
    }

    @Test
    void executeRange() {
        MvccStorageEngine engine = engine();
        for (int i = 0; i < 10; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse(
                        "SELECT * FROM kv WHERE key >= 'k0' AND key < 'k5'"),
                engine, Long.MAX_VALUE);
        assertThat(rows).hasSize(5);
    }

    @Test
    void executeLimit() {
        MvccStorageEngine engine = engine();
        for (int i = 0; i < 10; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse("SELECT * FROM kv LIMIT 3"),
                engine, Long.MAX_VALUE);
        assertThat(rows).hasSize(3);
    }

    @Test
    void executeSnapshotReadIgnoresFuture() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 20, WriteType.PUT);
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse("SELECT * FROM kv WHERE key = 'k'"),
                engine, 15);
        assertThat(rows.get(0).value()).isEqualTo(bytes("v1"));
    }

    @Test
    void executeIgnoresTombstone() {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse("SELECT * FROM kv WHERE key = 'k'"),
                engine, Long.MAX_VALUE);
        assertThat(rows).isEmpty();
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedExecuteRows(int count) {
        MvccStorageEngine engine = engine();
        for (int i = 0; i < count; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        List<SqlExecutor.Row> rows = new SqlExecutor().execute(
                new SqlParser().parse("SELECT * FROM kv"),
                engine, Long.MAX_VALUE);
        assertThat(rows).hasSize(count);
    }

    private static MvccStorageEngine engine() {
        return new MvccStorageEngine(MemTable.create());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
