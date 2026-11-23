package io.tieringkv.sql;

/** 只读 SQL 子集（ADR-0113）：SELECT * FROM kv [WHERE key ...] [LIMIT n]。 */
public record SelectStatement(byte[] exactKey, byte[] startKey,
                              byte[] endKey, int limit) {

    public SelectStatement {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        if (exactKey != null && (startKey != null || endKey != null)) {
            throw new IllegalArgumentException(
                    "exact key cannot combine with range");
        }
    }
}
