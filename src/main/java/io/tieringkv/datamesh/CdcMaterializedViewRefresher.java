package io.tieringkv.datamesh;

import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.MaterializedViewManager.Snapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 物化视图 CDC 增量刷新（ADR-0166）：变更流 → 增量聚合更新；
 * 失败回退全量刷新并标记 stale。
 */
public final class CdcMaterializedViewRefresher {

    public enum ChangeType {
        INSERT,
        UPDATE,
        DELETE
    }

    /** 变更事件：key + 类型 + 值。 */
    public record CdcChange(String key, ChangeType type,
                            double value) {

        public CdcChange {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "key required");
            }
            if (type == null) {
                throw new IllegalArgumentException(
                        "type required");
            }
        }
    }

    /** 视图内 key → value 状态（增量源）。 */
    private final Map<String, Map<String, Double>> viewValues =
            new ConcurrentHashMap<>();

    /** 应用变更：更新 key 状态并重算聚合写入快照。 */
    public boolean apply(MaterializedViewManager manager,
                         String viewId, Aggregate aggregate,
                         CdcChange change) {
        try {
            Map<String, Double> values = viewValues.computeIfAbsent(
                    viewId, ignored -> new ConcurrentHashMap<>());
            switch (change.type()) {
                case INSERT, UPDATE -> values.put(change.key(),
                        change.value());
                case DELETE -> values.remove(change.key());
            }
            AggregateResult result = aggregate(values, aggregate);
            manager.updateSnapshot(viewId, result.value(),
                    result.count());
            return true;
        } catch (RuntimeException e) {
            try {
                manager.invalidate(viewId);
            } catch (RuntimeException ignored) {
                // 视图不存在时无需失效
            }
            viewValues.remove(viewId);
            return false;
        }
    }

    /** 回退全量刷新：清空增量状态并执行完整跨云聚合。 */
    public void refreshFull(MaterializedViewManager manager,
                            String viewId, String coordinatorCloud,
                            Function<CloudShard, CloudResult>
                                    shardExecutor) {
        manager.refresh(viewId, coordinatorCloud, shardExecutor);
        viewValues.remove(viewId);
    }

    public int trackedKeys(String viewId) {
        Map<String, Double> values = viewValues.get(viewId);
        return values == null ? 0 : values.size();
    }

    private static AggregateResult aggregate(
            Map<String, Double> values, Aggregate aggregate) {
        if (values.isEmpty()) {
            return new AggregateResult(0, 0);
        }
        return switch (aggregate) {
            case SUM -> new AggregateResult(values.values().stream()
                    .mapToDouble(Double::doubleValue).sum(),
                    values.size());
            case COUNT -> new AggregateResult(values.size(),
                    values.size());
            case AVG -> {
                double total = values.values().stream()
                        .mapToDouble(Double::doubleValue).sum();
                yield new AggregateResult(total / values.size(),
                        values.size());
            }
            case MIN -> new AggregateResult(values.values().stream()
                    .mapToDouble(Double::doubleValue).min()
                    .orElse(0), values.size());
            case MAX -> new AggregateResult(values.values().stream()
                    .mapToDouble(Double::doubleValue).max()
                    .orElse(0), values.size());
        };
    }

    private record AggregateResult(double value, long count) {
    }
}
