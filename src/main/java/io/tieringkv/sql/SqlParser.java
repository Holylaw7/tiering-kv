package io.tieringkv.sql;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** SQL 子集解析器（ADR-0113）：SELECT/WHERE/LIMIT。 */
public final class SqlParser {

    public SelectStatement parse(String sql) {
        String normalized = sql.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("select * from kv")) {
            throw new IllegalArgumentException(
                    "only SELECT * FROM kv supported");
        }
        String rest = normalized.substring("select * from kv".length())
                .trim();
        byte[] exactKey = null;
        byte[] startKey = null;
        byte[] endKey = null;
        int limit = Integer.MAX_VALUE;
        while (!rest.isEmpty()) {
            if (rest.startsWith("where key")) {
                rest = rest.substring("where key".length()).trim();
                if (rest.startsWith("=")) {
                    rest = rest.substring(1).trim();
                    exactKey = quoted(rest);
                    rest = afterQuoted(rest);
                } else if (rest.startsWith(">=")) {
                    rest = rest.substring(2).trim();
                    startKey = quoted(rest);
                    rest = afterQuoted(rest);
                    if (rest.startsWith("and")) {
                        rest = rest.substring(3).trim();
                        if (rest.startsWith("key <")) {
                            rest = rest.substring("key <".length()).trim();
                            endKey = quoted(rest);
                            rest = afterQuoted(rest);
                        }
                    }
                } else {
                    throw new IllegalArgumentException(
                            "unsupported where clause");
                }
            } else if (rest.startsWith("limit")) {
                rest = rest.substring("limit".length()).trim();
                int space = rest.indexOf(' ');
                String number = space < 0 ? rest : rest.substring(0, space);
                try {
                    limit = Integer.parseInt(number);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad limit");
                }
                rest = space < 0 ? "" : rest.substring(space).trim();
            } else {
                throw new IllegalArgumentException(
                        "unsupported clause: " + rest);
            }
        }
        return new SelectStatement(exactKey, startKey, endKey, limit);
    }

    private static byte[] quoted(String rest) {
        if (!rest.startsWith("'")) {
            throw new IllegalArgumentException("expected quoted value");
        }
        int end = rest.indexOf('\'', 1);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated quote");
        }
        return rest.substring(1, end).getBytes(StandardCharsets.UTF_8);
    }

    private static String afterQuoted(String rest) {
        int end = rest.indexOf('\'', 1);
        return rest.substring(end + 1).trim();
    }

    private static String expectAnd(String rest) {
        if (rest.startsWith("and")) {
            return rest.substring(3).trim();
        }
        if (rest.isEmpty()) {
            return rest;
        }
        throw new IllegalArgumentException("expected AND");
    }
}
