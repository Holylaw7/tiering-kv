package io.tieringkv.sql;

/**
 * v4 阶段一：索引感知查询计划（RFC-0001）。基于 SqlIndexRegistry
 * 选择索引列扫描提示，additive 不改存储内核。
 */
public final class IndexAwarePlanner {

    /** 计划提示（ADR-0319）：含索引类型与维度。 */
    public record PlanHint(String table, String column,
                           boolean indexed, long entries,
                           SqlIndexRegistry.IndexType type,
                           int dimension) {
    }

    private final SqlIndexRegistry registry;

    public IndexAwarePlanner(SqlIndexRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException(
                    "registry required");
        }
        this.registry = registry;
    }

    public PlanHint plan(String table, String filterColumn) {
        if (table == null || table.isBlank()
                || filterColumn == null || filterColumn.isBlank()) {
            throw new IllegalArgumentException(
                    "table and filterColumn required");
        }
        boolean indexed = registry.hasIndex(table, filterColumn);
        long entries = indexed
                ? registry.index(table, filterColumn).entries()
                : 0;
        SqlIndexRegistry.IndexType type = indexed
                ? registry.index(table, filterColumn).type()
                : SqlIndexRegistry.IndexType.SCALAR;
        int dimension = indexed
                ? registry.index(table, filterColumn).dimension()
                : 0;
        return new PlanHint(table, filterColumn, indexed, entries,
                type, dimension);
    }

    /** 有索引且非空 → 推荐索引扫描。 */
    public boolean preferIndexedScan(String table,
                                     String filterColumn) {
        PlanHint hint = plan(table, filterColumn);
        return hint.indexed() && hint.entries() > 0;
    }
}
