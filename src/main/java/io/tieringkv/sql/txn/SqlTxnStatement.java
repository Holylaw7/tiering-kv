package io.tieringkv.sql.txn;

/** SQL 写事务语句（ADR-0128）。 */
public record SqlTxnStatement(Type type, byte[] key, byte[] value) {

    public enum Type {
        BEGIN,
        SET,
        DELETE,
        COMMIT,
        ROLLBACK
    }
}
