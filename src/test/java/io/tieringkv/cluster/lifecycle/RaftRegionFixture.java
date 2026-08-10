package io.tieringkv.cluster.lifecycle;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.multiraft.MultiRaftNode;
import io.tieringkv.cluster.multiraft.RaftGroupManager;
import io.tieringkv.cluster.raft.LocalRaftTransport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.routing.RoutingTable;
import io.tieringkv.cluster.routing.RoutingTableEntry;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 三节点多 Raft + Region + 路由夹具（Phase 18 集成测试）。 */
final class RaftRegionFixture implements AutoCloseable {

    final Map<String, RaftGroupManager> managers = new HashMap<>();
    final Map<String, MultiRaftNode> hosts = new HashMap<>();
    final Map<String, Map<String, List<RaftNode>>> peers = new HashMap<>();
    final List<RaftGroupManager> all = new ArrayList<>();
    final RegionManager regions = new RegionManager();
    final RoutingTable router = new RoutingTable();

    static RaftRegionFixture create() {
        RaftRegionFixture fixture = new RaftRegionFixture();
        for (String nodeId : List.of("n1", "n2", "n3")) {
            MultiRaftNode host = new MultiRaftNode(nodeId);
            RaftGroupManager manager = new RaftGroupManager(
                    nodeId, host, RaftTestSupport.ELECTION, 25, 10);
            fixture.managers.put(nodeId, manager);
            fixture.hosts.put(nodeId, host);
            fixture.all.add(manager);
        }
        return fixture;
    }

    RegionRaftMigrationManager managerOn(String nodeId) {
        return new RegionRaftMigrationManager(nodeId,
                managers.get(nodeId), regions, router);
    }

    /** 在所有节点创建同 id 的 Raft 组（每个节点独立存储）。 */
    void createGroupOnAll(String groupId,
                          Function<String, StorageEngine> storageForNode) {
        for (String nodeId : List.of("n1", "n2", "n3")) {
            peers.computeIfAbsent(groupId, ignored -> new HashMap<>())
                    .put(nodeId, new ArrayList<>());
        }
        for (String nodeId : List.of("n1", "n2", "n3")) {
            RaftGroupManager manager = managers.get(nodeId);
            manager.createGroup(groupId,
                    new LocalRaftTransport(peers.get(groupId).get(nodeId), nodeId),
                    storageForNode.apply(nodeId));
        }
        List<RaftNode> groupRafts = new ArrayList<>();
        for (String nodeId : List.of("n1", "n2", "n3")) {
            groupRafts.add(managers.get(nodeId).raftFor(groupId));
        }
        peers.get(groupId).values().forEach(list -> list.addAll(groupRafts));
        // 仅启动新组（避免重复 startAll 造成重复调度线程）
        for (String nodeId : List.of("n1", "n2", "n3")) {
            managers.get(nodeId).raftFor(groupId).start();
        }
    }

    void destroyGroupOnAll(String groupId) {
        for (RaftGroupManager manager : all) {
            manager.destroy(groupId);
        }
        peers.remove(groupId);
    }

    void put(RegionId regionId, String key, String value) {
        regions.get(regionId);
        router.route(bytes(key));
    }

    static void addRegion(RaftRegionFixture fixture, RegionId id,
                          String start, String end,
                          int slotStart, int slotEnd,
                          RegionEpoch epoch, String leader, String groupId) {
        fixture.regions.createRegion(id, bytes(start), bytes(end),
                List.of("n1", "n2", "n3"), epoch, leader);
        fixture.router.update(new RoutingTableEntry(id, bytes(start), bytes(end),
                slotStart, slotEnd, epoch, leader, groupId, false));
    }

    static MemTable load(MemTable table, int from, int count, String prefix) {
        byte[] value = new byte[16];
        for (int i = from; i < from + count; i++) {
            table.put(bytes(prefix + String.format("%04d", i)), value);
        }
        return table;
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        for (RaftGroupManager manager : all) {
            manager.close();
        }
    }
}
