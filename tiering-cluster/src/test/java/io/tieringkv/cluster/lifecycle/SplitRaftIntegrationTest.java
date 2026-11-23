package io.tieringkv.cluster.lifecycle;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionState;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.tieringkv.cluster.lifecycle.RaftRegionFixture.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Split 与 Raft 组联动（ADR-0067）：子组创建/数据/路由/回滚/恢复。 */
class SplitRaftIntegrationTest {

    private static final AtomicInteger VARIANT = new AtomicInteger();

    private RaftRegionFixture fixture;
    private MemTable source;
    private MemTable left;
    private MemTable right;
    private static final String LEFT_GROUP = "split-left";
    private static final String RIGHT_GROUP = "split-right";

    @BeforeEach
    void setUp() {
        fixture = RaftRegionFixture.create();
        RaftRegionFixture.addRegion(fixture, new RegionId(1),
                "a", "z", 0, 16_383,
                io.tieringkv.cluster.region.RegionEpoch.INITIAL, "n1", "parent");
        source = MemTable.create();
        right = MemTable.create(); // 空：数据由 SplitController 安装
        RaftRegionFixture.load(source, 0, 100, "sk:");
        left = MemTable.create(); // 空：数据由 SplitController 安装
        fixture.createGroupOnAll(LEFT_GROUP,
                node -> RaftRegionFixture.load(MemTable.create(), 0, 50, "sk:"));
        fixture.createGroupOnAll(RIGHT_GROUP,
                node -> RaftRegionFixture.load(MemTable.create(), 50, 50, "sk:"));
    }

    @AfterEach
    void tearDown() {
        fixture.close();
        source.close();
        left.close();
        right.close();
    }

    @Test
    void splitWithRaftCreatesChildRegions() {
        split();
        assertThat(fixture.regions.regionCount()).isEqualTo(2);
        assertThat(fixture.regions.get(new RegionId(11))).isNotNull();
        assertThat(fixture.regions.get(new RegionId(12))).isNotNull();
    }

    @Test
    void splitWithRaftInstallsDataIntoChildren() {
        split();
        assertThat(left.size() + right.size()).isEqualTo(100);
        assertThat(left.get(bytes("sk:0001"))).isNotNull();
        assertThat(right.get(bytes("sk:0099"))).isNotNull();
    }

    @Test
    void childGroupsRegisteredOnAllNodes() {
        split();
        for (String node : List.of("n1", "n2", "n3")) {
            assertThat(fixture.managers.get(node).raftFor(LEFT_GROUP)).isNotNull();
            assertThat(fixture.managers.get(node).raftFor(RIGHT_GROUP)).isNotNull();
        }
    }

    @Test
    void childGroupsElectLeaders() throws Exception {
        split();
        RaftNode leftLeader = RaftTestSupport.awaitLeader(
                groupRafts(LEFT_GROUP), 8000);
        RaftNode rightLeader = RaftTestSupport.awaitLeader(
                groupRafts(RIGHT_GROUP), 8000);
        assertThat(leftLeader).isNotNull();
        assertThat(rightLeader).isNotNull();
    }

    @Test
    void childGroupProposalReplicates() throws Exception {
        split();
        RaftNode leftLeader = RaftTestSupport.awaitLeader(
                groupRafts(LEFT_GROUP), 8000);
        fixture.managers.get(leftLeader.id()).storageFor(LEFT_GROUP)
                .put(bytes("sk:new"), bytes("v"));
        RaftTestSupport.awaitTrue("replicated", () ->
                groupRafts(LEFT_GROUP).stream()
                        .allMatch(n -> n.logSize() == 1), 8000);
    }

    @Test
    void routingSwitchedAfterSplit() {
        split();
        assertThat(fixture.router.route(bytes("sk:0001")).regionId())
                .isEqualTo(new RegionId(11));
        assertThat(fixture.router.route(bytes("sk:0099")).regionId())
                .isEqualTo(new RegionId(12));
    }

    @Test
    void oldRegionTombstone() {
        split();
        assertThat(fixture.regions.get(new RegionId(1)).state())
                .isEqualTo(RegionState.TOMBSTONE);
    }

    @Test
    void epochAdvancedAfterSplit() {
        split();
        assertThat(fixture.regions.get(new RegionId(11)).epoch().confVer())
                .isEqualTo(2);
    }

    @Test
    void splitOnFollowerNode() throws Exception {
        RegionRaftMigrationManager manager = fixture.managerOn("n2");
        manager.splitWithRaft(new RegionId(1), bytes("sk:0050"),
                source, left, right, LEFT_GROUP, RIGHT_GROUP,
                null, null, 0, 8191, 8192, 16383);
        assertThat(fixture.router.route(bytes("sk:0001")).regionId())
                .isEqualTo(new RegionId(11));
        RaftTestSupport.awaitLeader(groupRafts(LEFT_GROUP), 8000);
    }

    @Test
    void migrationFailureRollback() {
        FailingStorage failingRight = new FailingStorage();
        assertThatThrownBy(() -> fixture.managerOn("n1")
                .splitWithRaft(new RegionId(1), bytes("sk:0050"),
                        source, left, failingRight,
                        LEFT_GROUP, RIGHT_GROUP,
                        null, null, 0, 8191, 8192, 16383))
                .isInstanceOf(Throwable.class);
        assertThat(fixture.regions.get(new RegionId(1)).state())
                .isEqualTo(RegionState.NORMAL);
        assertThat(fixture.router.route(bytes("sk:0001")).regionId())
                .isEqualTo(new RegionId(1));
    }

    @Test
    void restartRecovery() throws Exception {
        split();
        fixture.destroyGroupOnAll(LEFT_GROUP);
        fixture.destroyGroupOnAll(RIGHT_GROUP);
        fixture.createGroupOnAll(LEFT_GROUP,
                node -> RaftRegionFixture.load(MemTable.create(), 0, 50, "sk:"));
        fixture.createGroupOnAll(RIGHT_GROUP,
                node -> RaftRegionFixture.load(MemTable.create(), 50, 50, "sk:"));
        RaftTestSupport.awaitLeader(groupRafts(LEFT_GROUP), 8000);
        assertThat(fixture.router.route(bytes("sk:0001")).regionId())
                .isEqualTo(new RegionId(11));
    }

    @Test
    void childRaftGroupIsolation() throws Exception {
        split();
        RaftNode leftLeader = RaftTestSupport.awaitLeader(
                groupRafts(LEFT_GROUP), 8000);
        fixture.managers.get(leftLeader.id()).storageFor(LEFT_GROUP)
                .put(bytes("sk:left-only"), bytes("v"));
        RaftTestSupport.awaitTrue("left replicated", () ->
                groupRafts(LEFT_GROUP).stream().allMatch(n -> n.logSize() == 1),
                8000);
        assertThat(groupRafts(RIGHT_GROUP).get(0).logSize()).isZero();
    }

    @Test
    void writeAfterSplitRoutesToChildren() {
        split();
        assertThat(fixture.router.route(bytes("sk:0010")).regionId())
                .isEqualTo(new RegionId(11));
        assertThat(fixture.router.route(bytes("sk:0090")).regionId())
                .isEqualTo(new RegionId(12));
    }

    @Test
    void slotRangesSwitched() {
        split();
        assertThat(fixture.router.routeSlot(0).regionId())
                .isEqualTo(new RegionId(11));
        assertThat(fixture.router.routeSlot(9000).regionId())
                .isEqualTo(new RegionId(12));
    }

    @Test
    void splitTwiceRejected() {
        split();
        assertThatThrownBy(() -> fixture.managerOn("n1")
                .splitWithRaft(new RegionId(1), bytes("sk:0025"),
                        source, left, right, LEFT_GROUP, RIGHT_GROUP,
                        null, null, 0, 4095, 4096, 8191))
                .isInstanceOf(Throwable.class);
    }

    @Test
    void childGroupsUseSeparateLogs() throws Exception {
        split();
        RaftNode leftLeader = RaftTestSupport.awaitLeader(
                groupRafts(LEFT_GROUP), 8000);
        fixture.managers.get(leftLeader.id()).storageFor(LEFT_GROUP)
                .put(bytes("sk:x"), bytes("v"));
        assertThat(groupRafts(RIGHT_GROUP).get(0).logSize()).isZero();
    }

    @Test
    void splitThenMergeBack() {
        split();
        fixture.createGroupOnAll("merged",
                node -> RaftRegionFixture.load(MemTable.create(), 0, 100, "sk:"));
        Region merged = fixture.managerOn("n1").mergeWithRaft(
                new RegionId(11), new RegionId(12),
                left, right, "merged", null, 0, 16_383);
        assertThat(merged.regionId().id()).isEqualTo(113);
        assertThat(fixture.router.route(bytes("sk:0001")).regionId())
                .isEqualTo(new RegionId(113));
    }

    @Test
    void splitPreservesParentDataTotal() {
        split();
        assertThat(left.size() + right.size()).isEqualTo(100);
    }

    @Test
    void routingEntriesCarryChildEpoch() {
        split();
        assertThat(fixture.router.route(bytes("sk:0001")).epoch().confVer())
                .isEqualTo(2);
    }

    @Test
    void splitWithEmptyLeftSide() {
        fixture.managerOn("n1").splitWithRaft(new RegionId(1),
                bytes("sk:0000"), source, left, right,
                LEFT_GROUP, RIGHT_GROUP, null, null,
                0, 0, 1, 16_383);
        assertThat(left.size()).isZero();
        assertThat(right.size()).isEqualTo(100);
    }

    @Test
    void splitWithLeaderChangeRouting() {
        split();
        fixture.router.update(fixture.router.route(bytes("sk:0001"))
                .withLeader("n2"));
        assertThat(fixture.router.route(bytes("sk:0001")).leader())
                .isEqualTo("n2");
    }

    @ParameterizedTest(name = "splitVariant {0}")
    @MethodSource("splitVariants")
    void splitVariants(String splitKey, int leftStart, int leftEnd,
                       int rightStart, int rightEnd, String leader) {
        int id = VARIANT.incrementAndGet();
        String leftGroup = "v-l-" + id;
        String rightGroup = "v-r-" + id;
        MemTable vLeft = MemTable.create(); // 空：数据由 SplitController 安装
        MemTable vRight = MemTable.create();
        fixture.createGroupOnAll(leftGroup,
                node -> MemTable.create());
        fixture.createGroupOnAll(rightGroup,
                node -> MemTable.create());
        fixture.managerOn(leader).splitWithRaft(new RegionId(1),
                bytes(splitKey), source, vLeft, vRight,
                leftGroup, rightGroup,
                null, null, leftStart, leftEnd, rightStart, rightEnd);
        assertThat(fixture.regions.regionCount()).isEqualTo(2);
        assertThat(vLeft.size() + vRight.size()).isEqualTo(100);
        vLeft.close();
        vRight.close();
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

    static Stream<Object[]> splitVariants() {
        return Stream.of(
                new Object[]{"sk:0025", 0, 4095, 4096, 8191, "n1"},
                new Object[]{"sk:0075", 0, 12287, 12288, 16383, "n2"},
                new Object[]{"sk:0010", 0, 1638, 1639, 16383, "n3"},
                new Object[]{"sk:0040", 0, 6553, 6554, 12287, "n1"},
                new Object[]{"sk:0060", 0, 9830, 9831, 16383, "n2"},
                new Object[]{"sk:0090", 0, 14745, 14746, 16383, "n3"},
                new Object[]{"sk:0005", 0, 819, 820, 8191, "n1"},
                new Object[]{"sk:0030", 0, 4915, 4916, 11468, "n2"},
                new Object[]{"sk:0055", 0, 9011, 9012, 16383, "n3"},
                new Object[]{"sk:0080", 0, 13107, 13108, 16383, "n1"},
                new Object[]{"sk:0020", 0, 3276, 3277, 8191, "n2"},
                new Object[]{"sk:0070", 0, 11468, 11469, 16383, "n3"});
    }

    private void split() {
        fixture.managerOn("n1").splitWithRaft(new RegionId(1),
                bytes("sk:0050"), source, left, right,
                LEFT_GROUP, RIGHT_GROUP, null, null,
                0, 8191, 8192, 16383);
    }

    private List<RaftNode> groupRafts(String groupId) {
        List<RaftNode> rafts = new ArrayList<>();
        for (String node : List.of("n1", "n2", "n3")) {
            rafts.add(fixture.managers.get(node).raftFor(groupId));
        }
        return rafts;
    }
}
