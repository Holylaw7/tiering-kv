package io.tieringkv.sql;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v4 阶段一：SQL 索引注册表（RFC-0001）。列索引注册 + 查询计划
 * 命中查询，additive 不改存储内核。
 */
public final class SqlIndexRegistry {

    /** 索引类型（ADR-0319）：标量列索引 / 向量索引。 */
    public enum IndexType { SCALAR, VECTOR }

    /** 索引描述。 */
    public record Index(String table, String column,
                        boolean unique, long entries, IndexType type,
                        int dimension) {
        public Index {
            if (type == null) {
                throw new IllegalArgumentException("type required");
            }
            if (dimension < 0) {
                throw new IllegalArgumentException(
                        "dimension >= 0");
            }
        }

        public static Index scalar(String table, String column,
                                   boolean unique, long entries) {
            return new Index(table, column, unique, entries,
                    IndexType.SCALAR, 0);
        }

        public static Index vector(String table, String column,
                                   long entries, int dimension) {
            return new Index(table, column, false, entries,
                    IndexType.VECTOR, dimension);
        }
    }

    private final Map<String, Index> indexes =
            new ConcurrentHashMap<>();

    public void register(String table, String column,
                         boolean unique, long entries) {
        if (table == null || table.isBlank()
                || column == null || column.isBlank()
                || entries < 0) {
            throw new IllegalArgumentException(
                    "table/column required and entries >= 0");
        }
        indexes.put(key(table, column),
                Index.scalar(table, column, unique, entries));
    }

    /** 注册向量索引（ADR-0319）：additive，标量注册语义不变。 */
    public void registerVector(String table, String column,
                               long entries, int dimension) {
        if (table == null || table.isBlank()
                || column == null || column.isBlank()
                || entries < 0 || dimension < 1) {
            throw new IllegalArgumentException(
                    "table/column required, entries >= 0, dimension >= 1");
        }
        indexes.put(key(table, column),
                Index.vector(table, column, entries, dimension));
    }

    public boolean hasIndex(String table, String column) {
        return indexes.containsKey(key(table, column));
    }

    public Index index(String table, String column) {
        return indexes.get(key(table, column));
    }

    public int size() {
        return indexes.size();
    }

    private static String key(String table, String column) {
        return table + "." + column;
    }
}
