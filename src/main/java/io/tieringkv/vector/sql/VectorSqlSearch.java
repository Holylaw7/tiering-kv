package io.tieringkv.vector.sql;

import io.tieringkv.sql.SqlIndexRegistry;
import io.tieringkv.vector.HybridSearch;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, String> collectionBindings =
            new ConcurrentHashMap<>();

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

    /** 绑定 SQL 索引 → 向量集合（ADR-0338）。 */
    public void bindCollection(String table, String vectorColumn,
                               String collection) {
        collectionBindings.put(key(table, vectorColumn), collection);
    }

    /** 集合感知检索：从注册表解析集合 store 后混合过滤。 */
    public List<VectorStore.ScoredEmbedding> search(
            VectorCollectionRegistry collections,
            String table, String vectorColumn,
            float[] query, int topK, Predicate<String> sqlPredicate) {
        String collection = collectionBindings.get(
                key(table, vectorColumn));
        if (collection == null) {
            throw new IllegalArgumentException(
                    "no vector collection bound for "
                            + table + "." + vectorColumn);
        }
        VectorIndexStore store = collections.collectionIfPresent(
                collection);
        if (store == null) {
            throw new IllegalArgumentException(
                    "collection not found: " + collection);
        }
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
        return hybridSearch.search(store.store(), query, topK,
                sqlPredicate);
    }

    private static String key(String table, String column) {
        return table + "." + column;
    }
}
