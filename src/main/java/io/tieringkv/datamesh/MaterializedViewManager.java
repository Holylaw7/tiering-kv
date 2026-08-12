package io.tieringkv.datamesh;

import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudAggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 跨云物化视图（ADR-0158）：预聚合 + 刷新 + 失效 + 查询；
 * 陈旧数据必须有 stale 标记，禁止无标记返回。
 */
public final class MaterializedViewManager {

    /** 视图定义：跨云分片 + 聚合 + 刷新周期。 */
    public record Definition(String viewId, List<CloudShard> shards,
                             Aggregate aggregate,
                             long refreshPeriodMillis) {

        public Definition {
            if (viewId == null || viewId.isBlank()) {
                throw new IllegalArgumentException(
                        "viewId required");
            }
            if (shards == null || shards.isEmpty()) {
                throw new IllegalArgumentException(
                        "shards required");
            }
            if (refreshPeriodMillis < 0) {
                throw new IllegalArgumentException(
                        "refresh period must be non-negative");
            }
            shards = List.copyOf(shards);
        }
    }

    /** 视图快照：数据 + stale 标记 + 刷新时间。 */
    public record Snapshot(String viewId, double value, long count,
                           boolean stale, long refreshedAtMillis) {
    }

    private final CloudFederatedExecutor executor;
    private final Map<String, Definition> definitions =
            new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots =
            new ConcurrentHashMap<>();

    public MaterializedViewManager(CloudFederatedExecutor executor) {
        this.executor = executor;
    }

    public void create(Definition definition) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "definition required");
        }
        if (definitions.putIfAbsent(definition.viewId(),
                definition) != null) {
            throw new IllegalArgumentException(
                    "view already exists: " + definition.viewId());
        }
        snapshots.put(definition.viewId(), new Snapshot(
                definition.viewId(), 0, 0, true, 0));
    }

    /** 刷新：跨云聚合 → 新快照（stale=false）。 */
    public Snapshot refresh(String viewId, String coordinatorCloud,
                            Function<CloudShard, CloudResult>
                                    shardExecutor) {
        Definition definition = require(viewId);
        CloudAggregate aggregate = executor.execute(
                coordinatorCloud, definition.shards(), shardExecutor,
                definition.aggregate());
        Snapshot snapshot = new Snapshot(viewId,
                aggregate.value(), aggregate.count(), false,
                System.currentTimeMillis());
        snapshots.put(viewId, snapshot);
        return snapshot;
    }

    /** 按周期到期刷新：未到期返回 false。 */
    public boolean refreshIfDue(String viewId, String coordinatorCloud,
                                Function<CloudShard, CloudResult>
                                        shardExecutor) {
        Definition definition = require(viewId);
        Snapshot current = snapshots.get(viewId);
        long period = definition.refreshPeriodMillis();
        if (period > 0 && current != null && !current.stale()
                && System.currentTimeMillis()
                - current.refreshedAtMillis() < period) {
            return false;
        }
        refresh(viewId, coordinatorCloud, shardExecutor);
        return true;
    }

    /** 失效：标记 stale，不删除定义。 */
    public Snapshot invalidate(String viewId) {
        Definition definition = require(viewId);
        Snapshot current = snapshots.get(viewId);
        Snapshot invalidated = new Snapshot(viewId,
                current == null ? 0 : current.value(),
                current == null ? 0 : current.count(),
                true, current == null ? 0
                        : current.refreshedAtMillis());
        snapshots.put(viewId, invalidated);
        return invalidated;
    }

    /** 增量快照写入：CDC 刷新结果（stale=false）。 */
    public Snapshot updateSnapshot(String viewId, double value,
                                   long count) {
        require(viewId);
        Snapshot snapshot = new Snapshot(viewId, value, count,
                false, System.currentTimeMillis());
        snapshots.put(viewId, snapshot);
        return snapshot;
    }

    /** 查询：返回快照（含 stale 标记），不存在抛异常。 */
    public Snapshot query(String viewId) {
        Snapshot snapshot = snapshots.get(viewId);
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "unknown view " + viewId);
        }
        return snapshot;
    }

    public boolean isStale(String viewId) {
        return query(viewId).stale();
    }

    public Set<String> viewIds() {
        return Set.copyOf(definitions.keySet());
    }

    public int size() {
        return definitions.size();
    }

    public void drop(String viewId) {
        definitions.remove(viewId);
        snapshots.remove(viewId);
    }

    private Definition require(String viewId) {
        Definition definition = definitions.get(viewId);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "unknown view " + viewId);
        }
        return definition;
    }
}
