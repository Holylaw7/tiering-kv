package io.tieringkv.vector.sql;

import io.tieringkv.sql.SqlIndexRegistry;
import io.tieringkv.vector.HybridSearch;
import io.tieringkv.vector.VectorStore;

import java.util.List;
import java.util.function.Predicate;

/**
 * SQL 向量混合检索（ADR-0319）：校验向量索引计划后委托 HybridSearch。
 *
 * <p>流程：SQL 计划命中 vector 列 → 校验查询维度与索引维度一致 →
 * 向量 top-K 候选 → 标量谓词（SQL WHERE 映射到 id/元数据）过滤。
 */
public final class VectorSqlSearch {

    private final SqlIndexRegistry registry;
    private final HybridSearch hybridSearch = new HybridSearch();

    public VectorSqlSearch(SqlIndexRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry required");
        }
        this.registry = registry;
    }

    public List<VectorStore.ScoredEmbedding> search(
            VectorStore store, String table, String vectorColumn,
            float[] query, int topK, Predicate<String> sqlPredicate) {
        SqlIndexRegistry.Index index = registry.index(table,
                vectorColumn);
        if (index == null
                || index.type() != SqlIndexRegistry.IndexType.VECTOR) {
            throw new IllegalArgumentException(
                    "vector index required for "
                            + table + "." + vectorColumn);
        }
        if (query.length != index.dimension()) {
            throw new IllegalArgumentException(
                    "query dim " + query.length
                            + " != index dim " + index.dimension());
        }
        if (topK < 0) {
            throw new IllegalArgumentException("topK >= 0");
        }
        return hybridSearch.search(store, query, topK, sqlPredicate);
    }
}
