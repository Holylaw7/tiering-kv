package io.tieringkv.sql;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v4 阶段一：SQL 索引注册表（RFC-0001）。列索引注册 + 查询计划
 * 命中查询，additive 不改存储内核。
 */
public final class SqlIndexRegistry {

    /** 索引描述。 */
    public record Index(String table, String column,
                        boolean unique, long entries) {
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
                new Index(table, column, unique, entries));
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
