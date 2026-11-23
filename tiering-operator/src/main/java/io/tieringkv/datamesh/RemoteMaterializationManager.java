package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.CdcChange;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跨云远端物化（ADR-0173）：远端落盘 + CDC 增量同步 +
 * 数据主权拒绝。
 */
public final class RemoteMaterializationManager {

    /** 远端物化定义：视图 + 远端云 + 协调器云 + 分片 + 聚合。 */
    public record RemoteDefinition(String viewId, String remoteCloud,
                                   String coordinatorCloud,
                                   List<CloudShard> shards,
                                   Aggregate aggregate) {

        public RemoteDefinition {
            if (viewId == null || viewId.isBlank()) {
                throw new IllegalArgumentException(
                        "viewId required");
            }
            if (remoteCloud == null || remoteCloud.isBlank()
                    || coordinatorCloud == null
                    || coordinatorCloud.isBlank()) {
                throw new IllegalArgumentException(
                        "clouds required");
            }
            if (shards == null || shards.isEmpty()) {
                throw new IllegalArgumentException(
                        "shards required");
            }
            shards = List.copyOf(shards);
        }
    }

    /** 远端物化快照。 */
    public record RemoteSnapshot(String viewId, String remoteCloud,
                                 double value, long count,
                                 boolean stale,
                                 long refreshedAtMillis) {
    }

    private static final class RemoteViewState {
        volatile double value;
        volatile long count;
        volatile boolean stale = true;
        volatile long refreshedAtMillis;
        final Map<String, Double> keys = new ConcurrentHashMap<>();
    }

    private final ComplianceValidator validator;
    private final DataResidencyPolicy policy;
    private final CloudFederatedExecutor executor;
    private final Map<String, RemoteDefinition> definitions =
            new ConcurrentHashMap<>();
    private final Map<String, RemoteViewState> states =
            new ConcurrentHashMap<>();

    public RemoteMaterializationManager(
            ComplianceValidator validator,
            DataResidencyPolicy policy) {
        this.validator = validator;
        this.policy = policy;
        this.executor = new CloudFederatedExecutor(validator,
                policy);
    }

    /** 定义远端物化：先做主权校验（跨驻留拒绝）。 */
    public void define(RemoteDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "definition required");
        }
        requireSingleResidency(definition);
        if (definitions.putIfAbsent(definition.viewId(),
                definition) != null) {
            throw new IllegalArgumentException(
                    "remote view already exists: "
                            + definition.viewId());
        }
        states.put(definition.viewId(), new RemoteViewState());
    }

    /** CDC 增量同步：变更应用到远端快照。 */
    public RemoteSnapshot syncChange(String viewId,
                                     CdcChange change) {
        RemoteDefinition definition = require(viewId);
        RemoteViewState state = states.get(viewId);
        switch (change.type()) {
            case INSERT, UPDATE -> state.keys.put(change.key(),
                    change.value());
            case DELETE -> state.keys.remove(change.key());
        }
        AggregateResult result = aggregate(state.keys,
                definition.aggregate());
        state.value = result.value();
        state.count = result.count();
        state.stale = false;
        state.refreshedAtMillis = System.currentTimeMillis();
        return snapshot(definition, state);
    }

    /** 全量刷新：跨云聚合 → 远端快照，清空增量状态。 */
    public RemoteSnapshot refreshFull(
            String viewId,
            Function<CloudShard, CloudResult> shardExecutor) {
        RemoteDefinition definition = require(viewId);
        CloudFederatedExecutor.CloudAggregate aggregate =
                executor.execute(definition.coordinatorCloud(),
                        definition.shards(), shardExecutor,
                        definition.aggregate());
        RemoteViewState state = states.get(viewId);
        state.value = aggregate.value();
        state.count = aggregate.count();
        state.stale = false;
        state.refreshedAtMillis = System.currentTimeMillis();
        state.keys.clear();
        return snapshot(definition, state);
    }

    public RemoteSnapshot snapshot(String viewId) {
        return snapshot(require(viewId), states.get(viewId));
    }

    public RemoteSnapshot invalidate(String viewId) {
        RemoteViewState state = states.get(viewId);
        require(viewId);
        state.stale = true;
        return snapshot(require(viewId), state);
    }

    public boolean isStale(String viewId) {
        return snapshot(viewId).stale();
    }

    public Set<String> viewIds() {
        return Set.copyOf(definitions.keySet());
    }

    public int size() {
        return definitions.size();
    }

    private RemoteDefinition require(String viewId) {
        RemoteDefinition definition = definitions.get(viewId);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "unknown remote view " + viewId);
        }
        return definition;
    }

    private void requireSingleResidency(
            RemoteDefinition definition) {
        Set<String> residencies = definition.shards().stream()
                .map(shard -> policy.required(shard.cloud()))
                .collect(Collectors.toSet());
        residencies.add(policy.required(
                definition.remoteCloud()));
        residencies.add(policy.required(
                definition.coordinatorCloud()));
        if (residencies.size() > 1) {
            throw new SecurityException(
                    "cross-residency materialization denied: "
                            + residencies);
        }
        for (CloudShard shard : definition.shards()) {
            validator.validate(policy,
                    definition.coordinatorCloud(),
                    shard.cloud());
        }
    }

    private static RemoteSnapshot snapshot(
            RemoteDefinition definition, RemoteViewState state) {
        return new RemoteSnapshot(definition.viewId(),
                definition.remoteCloud(), state.value,
                state.count, state.stale,
                state.refreshedAtMillis);
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
