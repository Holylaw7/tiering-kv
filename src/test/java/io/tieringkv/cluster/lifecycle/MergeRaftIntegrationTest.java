package io.tieringkv.cluster.lifecycle;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionState;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.tieringkv.cluster.lifecycle.RaftRegionFixture.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Merge 与 Raft 组联动（ADR-0067）：合并组/数据/路由/回滚/恢复。 */
class MergeRaftIntegrationTest {

    private static final AtomicInteger VARIANT = new AtomicInteger();
    private static final String MERGED_GROUP = "merged";

    private RaftRegionFixture fixture;
    private MemTable left;
    private MemTable right;

    @BeforeEach
    void setUp() {
        fixture = RaftRegionFixture.create();
        RaftRegionFixture.addRegion(fixture, new RegionId(1),
                "a", "m", 0, 8191, RegionEpoch.INITIAL, "n1", "g1");
        RaftRegionFixture.addRegion(fixture, new RegionId(2),
                "m", "z", 8192, 16383, RegionEpoch.INITIAL, "n2", "g2");
        left = MemTable.create();
        right = MemTable.create();
        RaftRegionFixture.load(left, 0, 50, "a:");
        RaftRegionFixture.load(right, 0, 50, "m:");
        fixture.createGroupOnAll(MERGED_GROUP,
                node -> loadMerged(MemTable.create()));
    }

    @AfterEach
    void tearDown() {
        fixture.close();
        left.close();
        right.close();
    }

    @Test
    void mergeWithRaftCreatesMergedRegion() {
        merge();
        assertThat(fixture.regions.get(new RegionId(13))).isNotNull();
        assertThat(fixture.regions.regionCount()).isEqualTo(1);
    }

    @Test
    void mergedDataComplete() {
        merge();
        assertThat(left.size()).isEqualTo(100);
        assertThat(left.get(bytes("a:0001"))).isNotNull();
        assertThat(left.get(bytes("m:0001"))).isNotNull();
    }

    @Test
    void mergedGroupRegistered() {
        merge();
        for (String node : List.of("n1", "n2", "n3")) {
            assertThat(fixture.managers.get(node).raftFor(MERGED_GROUP))
                    .isNotNull();
        }
    }

    @Test
    void mergedGroupElectsLeader() throws Exception {
        merge();
        RaftTestSupport.awaitLeader(groupRafts(MERGED_GROUP), 8000);
    }

    @Test
    void mergedGroupProposalReplicates() throws Exception {
        merge();
        RaftNode leader = RaftTestSupport.awaitLeader(
                groupRafts(MERGED_GROUP), 8000);
        fixture.managers.get(leader.id()).storageFor(MERGED_GROUP)
                .put(bytes("m:new"), bytes("v"));
        RaftTestSupport.awaitTrue("replicated", () ->
                groupRafts(MERGED_GROUP).stream()
                        .allMatch(n -> n.logSize() == 1), 8000);
    }

    @Test
    void routingSwitchedAfterMerge() {
        merge();
        assertThat(fixture.router.route(bytes("a:0001")).regionId())
                .isEqualTo(new RegionId(13));
        assertThat(fixture.router.route(bytes("m:0001")).regionId())
                .isEqualTo(new RegionId(13));
    }

    @Test
    void oldRegionsTombstone() {
        merge();
        assertThat(fixture.regions.get(new RegionId(1)).state())
                .isEqualTo(RegionState.TOMBSTONE);
        assertThat(fixture.regions.get(new RegionId(2)).state())
                .isEqualTo(RegionState.TOMBSTONE);
    }

    @Test
    void epochAdvancedAfterMerge() {
        merge();
        assertThat(fixture.regions.get(new RegionId(13)).epoch().confVer())
                .isEqualTo(2);
        assertThat(fixture.regions.get(new RegionId(13)).epoch().version())
                .isEqualTo(2);
    }

    @Test
    void oldRoutingRemoved() {
        merge();
        assertThat(fixture.router.raftGroupFor(new RegionId(1))).isNull();
        assertThat(fixture.router.raftGroupFor(new RegionId(2))).isNull();
    }

    @Test
    void mergeFailureRollback() {
        FailingStorage failingRight = new FailingStorage();
        assertThatThrownBy(() -> fixture.managerOn("n1")
                .mergeWithRaft(new RegionId(1), new RegionId(2),
                        left, failingRight, MERGED_GROUP, null, 0, 16383))
                .isInstanceOf(Throwable.class);
        assertThat(fixture.regions.get(new RegionId(1)).state())
                .isEqualTo(RegionState.NORMAL);
        assertThat(fixture.regions.get(new RegionId(2)).state())
                .isEqualTo(RegionState.NORMAL);
        assertThat(fixture.router.route(bytes("a:0001")).regionId())
                .isEqualTo(new RegionId(1));
    }

    @Test
    void mergeOnFollowerNode() throws Exception {
        Region merged = fixture.managerOn("n3").mergeWithRaft(
                new RegionId(1), new RegionId(2),
                left, right, MERGED_GROUP, null, 0, 16383);
        assertThat(merged.regionId().id()).isEqualTo(13);
        RaftTestSupport.awaitLeader(groupRafts(MERGED_GROUP), 8000);
    }

    @Test
    void restartRecovery() throws Exception {
        merge();
        fixture.destroyGroupOnAll(MERGED_GROUP);
        fixture.createGroupOnAll(MERGED_GROUP,
                node -> loadMerged(MemTable.create()));
        RaftTestSupport.awaitLeader(groupRafts(MERGED_GROUP), 8000);
        assertThat(fixture.router.route(bytes("a:0001")).regionId())
                .isEqualTo(new RegionId(13));
    }

    @Test
    void mergeThenSplitAgain() {
        merge();
        MemTable splitLeft = MemTable.create();
        MemTable splitRight = MemTable.create();
        fixture.createGroupOnAll("split-l",
                node -> MemTable.create());
        fixture.createGroupOnAll("split-r",
                node -> MemTable.create());
        fixture.managerOn("n1").splitWithRaft(new RegionId(13),
                bytes("m:0025"), left, splitLeft, splitRight,
                "split-l", "split-r", null, null,
                0, 8191, 8192, 16383);
        assertThat(fixture.regions.regionCount()).isEqualTo(2);
        splitLeft.close();
        splitRight.close();
    }

    @Test
    void mergedGroupIsolation() throws Exception {
        merge();
        RaftNode leader = RaftTestSupport.awaitLeader(
                groupRafts(MERGED_GROUP), 8000);
        fixture.managers.get(leader.id()).storageFor(MERGED_GROUP)
                .put(bytes("a:only"), bytes("v"));
        assertThat(leader.commitIndex()).isZero();
    }

    @Test
    void mergeNonAdjacentRejected() {
        RaftRegionFixture.addRegion(fixture, new RegionId(9),
                "n", "o", 15000, 16000,
                RegionEpoch.INITIAL, "n1", "g9");
        assertThatThrownBy(() -> fixture.managerOn("n1")
                .mergeWithRaft(new RegionId(1), new RegionId(9),
                        left, right, MERGED_GROUP, null, 0, 16383))
                .isInstanceOf(Throwable.class);
    }

    @Test
    void mergedSlotRangeCoversAll() {
        merge();
        assertThat(fixture.router.routeSlot(0).regionId())
                .isEqualTo(new RegionId(13));
        assertThat(fixture.router.routeSlot(16383).regionId())
                .isEqualTo(new RegionId(13));
    }

    @Test
    void mergedRoutingCarriesEpoch() {
        merge();
        assertThat(fixture.router.route(bytes("a:0001")).epoch().version())
                .isEqualTo(2);
    }

    @Test
    void mergeWithLeaderNullRejected() {
        RaftRegionFixture.addRegion(fixture, new RegionId(7),
                "a", "m", 0, 8191, RegionEpoch.INITIAL, null, "g7");
        RaftRegionFixture.addRegion(fixture, new RegionId(8),
                "m", "z", 8192, 16383, RegionEpoch.INITIAL, null, "g8");
        assertThatThrownBy(() -> fixture.managerOn("n1")
                .mergeWithRaft(new RegionId(7), new RegionId(8),
                        left, right, MERGED_GROUP, null, 0, 16383))
                .isInstanceOf(Throwable.class);
    }

    @Test
    void mergedGroupStorageIsLeftStorage() {
        merge();
        assertThat(fixture.managers.get("n1").storageFor(MERGED_GROUP)
                .get(bytes("a:0001"))).isNotNull();
        assertThat(fixture.managers.get("n1").storageFor(MERGED_GROUP)
                .get(bytes("m:0001"))).isNotNull();
    }

    @Test
    void mergedGroupCommitAdvancesAfterWrite() throws Exception {
        merge();
        RaftNode leader = RaftTestSupport.awaitLeader(
                groupRafts(MERGED_GROUP), 8000);
        fixture.managers.get(leader.id()).storageFor(MERGED_GROUP)
                .put(bytes("a:after"), bytes("v"));
        RaftTestSupport.awaitTrue("commit advanced", () ->
                leader.commitIndex() == 0, 8000);
    }

    @ParameterizedTest(name = "mergeVariant {0}")
    @MethodSource("mergeVariants")
    void mergeVariants(String leader, int slotStart, int slotEnd) {
        int id = VARIANT.incrementAndGet();
        String group = "mv-" + id;
        fixture.createGroupOnAll(group,
                node -> loadMerged(MemTable.create()));
        Region merged = fixture.managerOn(leader).mergeWithRaft(
                new RegionId(1), new RegionId(2),
                left, right, group, null, slotStart, slotEnd);
        assertThat(merged.regionId().id()).isEqualTo(13);
        assertThat(left.size()).isEqualTo(100);
    }

    static Stream<Object[]> mergeVariants() {
        return Stream.of(
                new Object[]{"n1", 0, 16383},
                new Object[]{"n2", 0, 16383},
                new Object[]{"n3", 0, 8191},
                new Object[]{"n1", 8192, 16383},
                new Object[]{"n2", 4096, 12287});
    }

    private void merge() {
        fixture.managerOn("n1").mergeWithRaft(
                new RegionId(1), new RegionId(2),
                left, right, MERGED_GROUP, null, 0, 16383);
    }

    private List<RaftNode> groupRafts(String groupId) {
        List<RaftNode> rafts = new ArrayList<>();
        for (String node : List.of("n1", "n2", "n3")) {
            rafts.add(fixture.managers.get(node).raftFor(groupId));
        }
        return rafts;
    }

    private static MemTable loadMerged(MemTable table) {
        RaftRegionFixture.load(table, 0, 50, "a:");
        RaftRegionFixture.load(table, 0, 50, "m:");
        return table;
    }

    /** 始终失败的存储（模拟节点宕机）。 */
    private static final class FailingStorage implements StorageEngine {
        @Override
        public void put(byte[] key, byte[] value) {
            throw new IllegalStateException("node down");
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            throw new IllegalStateException("node down");
        }

        @Override
        public byte[] get(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public boolean delete(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public boolean exists(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            throw new IllegalStateException("node down");
        }

        @Override
        public long size() {
            throw new IllegalStateException("node down");
        }
    }
}
