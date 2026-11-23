package io.tieringkv.datamesh;

import java.util.ArrayList;
import java.util.List;

/** 数据网格联邦规划（ADR-0148）：跨域查询 → 分片计划 + 域隔离。 */
public final class FederatedPlanner {

    public enum Aggregate {
        SUM,
        COUNT,
        AVG,
        MIN,
        MAX
    }

    /** 联邦查询：指标 + 聚合 + 目标域列表。 */
    public record Query(String metric, String aggregate,
                        List<String> domains) {
    }

    /** 单域分片。 */
    public record Shard(String domainId, String metric) {
    }

    /** 联邦执行计划。 */
    public record Plan(String metric, Aggregate aggregate,
                       List<Shard> shards) {

        public Plan {
            shards = List.copyOf(shards);
        }
    }

    private final DomainCatalog catalog;

    public FederatedPlanner(DomainCatalog catalog) {
        this.catalog = catalog;
    }

    /** 规划前校验：域存在 + 角色授权（域隔离）。 */
    public Plan plan(Query query, String role) {
        if (query == null || query.metric() == null
                || query.metric().isBlank()) {
            throw new IllegalArgumentException(
                    "metric required");
        }
        if (query.aggregate() == null
                || query.aggregate().isBlank()) {
            throw new IllegalArgumentException(
                    "aggregate required");
        }
        if (query.domains() == null || query.domains().isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one domain required");
        }
        Aggregate aggregate = Aggregate.valueOf(
                query.aggregate().toUpperCase());
        List<Shard> shards = new ArrayList<>();
        for (String domainId : query.domains()) {
            DomainCatalog.Domain domain = catalog.require(domainId);
            if (!domain.allowedRoles().contains(role)) {
                throw new SecurityException(
                        "domain access denied: " + domainId);
            }
            shards.add(new Shard(domainId, query.metric()));
        }
        return new Plan(query.metric(), aggregate, shards);
    }
}
