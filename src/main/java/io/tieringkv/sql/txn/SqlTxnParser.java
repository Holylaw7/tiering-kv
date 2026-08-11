package io.tieringkv.sql.txn;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** SQL 写事务解析器（ADR-0128）：BEGIN/SET/DELETE/COMMIT/ROLLBACK。 */
public final class SqlTxnParser {

    public SqlTxnStatement parse(String sql) {
        String normalized = sql.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        if (normalized.equals("begin")) {
            return new SqlTxnStatement(SqlTxnStatement.Type.BEGIN,
                    null, null);
        }
        if (normalized.equals("commit")) {
            return new SqlTxnStatement(SqlTxnStatement.Type.COMMIT,
                    null, null);
        }
        if (normalized.equals("rollback")) {
            return new SqlTxnStatement(SqlTxnStatement.Type.ROLLBACK,
                    null, null);
        }
        if (normalized.startsWith("set ")) {
            String rest = normalized.substring(4).trim();
            String key = quoted(rest);
            rest = afterQuoted(rest);
            if (!rest.startsWith("=")) {
                throw new IllegalArgumentException("expected =");
            }
            rest = rest.substring(1).trim();
            String value = quoted(rest);
            return new SqlTxnStatement(SqlTxnStatement.Type.SET,
                    key.getBytes(StandardCharsets.UTF_8),
                    value.getBytes(StandardCharsets.UTF_8));
        }
        if (normalized.startsWith("delete ")) {
            String rest = normalized.substring(7).trim();
            if (!rest.startsWith("from kv where key =")) {
                throw new IllegalArgumentException(
                        "expected DELETE FROM kv WHERE key = '...'");
            }
            rest = rest.substring("from kv where key =".length())
                    .trim();
            String key = quoted(rest);
            return new SqlTxnStatement(SqlTxnStatement.Type.DELETE,
                    key.getBytes(StandardCharsets.UTF_8), null);
        }
        throw new IllegalArgumentException("unsupported txn sql");
    }

    private static String quoted(String rest) {
        if (!rest.startsWith("'")) {
            throw new IllegalArgumentException("expected quoted value");
        }
        int end = rest.indexOf('\'', 1);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated quote");
        }
        return rest.substring(1, end);
    }

    private static String afterQuoted(String rest) {
        int end = rest.indexOf('\'', 1);
        return rest.substring(end + 1).trim();
    }
}
