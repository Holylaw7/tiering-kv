package io.tieringkv.cluster.routing;

import io.tieringkv.cluster.region.RegionId;

/** 统一路由（ADR-0066）：key → slot → region → raftGroup。 */
public interface UnifiedRouter {

    RoutingTableEntry route(byte[] key);

    RoutingTableEntry routeSlot(int slot);

    String raftGroupFor(RegionId regionId);

    long version();

    void update(RoutingTableEntry entry);
}
