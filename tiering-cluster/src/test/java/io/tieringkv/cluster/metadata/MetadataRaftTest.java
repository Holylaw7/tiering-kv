package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.sharding.ShardId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 元数据 Raft 化（ADR-0047）：命令编解码 / 状态机 / 组复制 / 故障转移。 */
class MetadataRaftTest {

    @Test
    void codecJoinRoundTrip() {
        MetadataCodec.Decoded decoded = MetadataCodec.decode(MetadataCodec.join("n1"));
        assertThat(decoded.type()).isEqualTo(MetadataCommandType.JOIN);
        assertThat(decoded.nodeId()).isEqualTo("n1");
    }

    @Test
    void codecLeaveRoundTrip() {
        MetadataCodec.Decoded decoded = MetadataCodec.decode(MetadataCodec.leave("n2"));
        assertThat(decoded.type()).isEqualTo(MetadataCommandType.LEAVE);
        assertThat(decoded.nodeId()).isEqualTo("n2");
    }

    @Test
    void codecCreateShardRoundTrip() {
        byte[] command = MetadataCodec.createShard(new ShardId(0),
                List.of("n1", "n2", "n3"), "n1");
        MetadataCodec.Decoded decoded = MetadataCodec.decode(command);
        assertThat(decoded.type()).isEqualTo(MetadataCommandType.CREATE_SHARD);
        assertThat(decoded.shardId()).isEqualTo(new ShardId(0));
        assertThat(decoded.nodes()).containsExactly("n1", "n2", "n3");
        assertThat(decoded.leader()).isEqualTo("n1");
    }

    @Test
    void codecCreateShardNullLeaderRoundTrip() {
        MetadataCodec.Decoded decoded = MetadataCodec.decode(
                MetadataCodec.createShard(new ShardId(1), List.of("a", "b"), null));
        assertThat(decoded.leader()).isNull();
    }

    @Test
    void codecUpdateLeaderRoundTrip() {
        MetadataCodec.Decoded decoded = MetadataCodec.decode(
                MetadataCodec.updateLeader(3, "n2"));
        assertThat(decoded.type()).isEqualTo(MetadataCommandType.UPDATE_LEADER);
        assertThat(decoded.shardIdValue()).isEqualTo(3);
        assertThat(decoded.leader()).isEqualTo("n2");
    }

    @Test
    void codecAssignSlotsRoundTrip() {
        MetadataCodec.Decoded decoded = MetadataCodec.decode(MetadataCodec.assignSlots(4));
        assertThat(decoded.type()).isEqualTo(MetadataCommandType.ASSIGN_SLOTS);
        assertThat(decoded.shardCount()).isEqualTo(4);
    }

    @Test
    void codecMigrationStatusRoundTrip() {
        MetadataCodec.Decoded decoded = MetadataCodec.decode(
                MetadataCodec.migrationStatus(2, "COPYING"));
        assertThat(decoded.type()).isEqualTo(MetadataCommandType.MIGRATION_STATUS);
        assertThat(decoded.shardIdValue()).isEqualTo(2);
        assertThat(decoded.migrationStatus()).isEqualTo("COPYING");
    }

    @Test
    void stateApplyJoin() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.join("n1"));
        state.apply(MetadataCodec.join("n2"));
        assertThat(state.nodes().size()).isEqualTo(2);
        assertThat(state.nodes().contains("n1")).isTrue();
    }

    @Test
    void stateApplyLeave() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.join("n1"));
        state.apply(MetadataCodec.leave("n1"));
        assertThat(state.nodes().size()).isZero();
    }

    @Test
    void stateApplyCreateShard() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.createShard(new ShardId(0),
                List.of("n1", "n2"), "n1"));
        assertThat(state.topology().shardRegistry().get(0).nodes())
                .containsExactly("n1", "n2");
        assertThat(state.topology().shardRegistry().get(0).leader()).isEqualTo("n1");
    }

    @Test
    void stateApplyUpdateLeader() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.createShard(new ShardId(0), List.of("n1", "n2"), "n1"));
        state.apply(MetadataCodec.updateLeader(0, "n2"));
        assertThat(state.topology().shardRegistry().get(0).leader()).isEqualTo("n2");
    }

    @Test
    void stateApplyAssignSlots() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.assignSlots(3));
        assertThat(state.topology().slotTable().shardFor(100)).isEqualTo(1);
        assertThat(state.topology().slotTable().shardFor(16383)).isEqualTo(0);
    }

    @Test
    void stateApplyMigrationStatus() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.migrationStatus(1, "SWITCHING"));
        assertThat(state.migrationStatus(1)).isEqualTo("SWITCHING");
        assertThat(state.migrationStatus(9)).isEqualTo("UNKNOWN");
    }

    @Test
    void invalidCommandRejected() {
        MetadataState state = new MetadataState();
        assertThatThrownBy(() -> state.apply(new byte[]{99, 1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupElectsLeader() throws Exception {
        try (Fixture fixture = fixture()) {
            assertThat(fixture.group().leader()).isNotNull();
        }
    }

    @Test
    void joinVisibleAcrossAllNodes() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.client().join("storage-1");
            for (RaftNode node : fixture.group().nodes()) {
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline
                        && !fixture.group().state(node.id()).nodes().contains("storage-1")) {
                    Thread.sleep(10);
                }
                assertThat(fixture.group().state(node.id()).nodes().contains("storage-1"))
                        .isTrue();
            }
        }
    }

    @Test
    void createShardAndUpdateLeaderVisible() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.client().join("n1");
            fixture.client().join("n2");
            fixture.client().createShard(new ShardId(0), List.of("n1", "n2"), "n1");
            fixture.client().updateLeader(0, "n2");
            assertThat(fixture.client().state().topology().shardRegistry().get(0).leader())
                    .isEqualTo("n2");
        }
    }

    @Test
    void leaderFailoverKeepsMetadataAvailable() throws Exception {
        Fixture fixture = fixture();
        RaftNode leader = awaitLeader(fixture.group().nodes(), 5000);
        fixture.client().join("meta-safe");
        leader.suspend();
        leader.close();
        RaftNode newLeader = awaitLeader(fixture.group().nodes(), 5000);
        assertThat(newLeader).isNotEqualTo(leader);
        fixture.client().join("after-failover");
        assertThat(fixture.client().state().nodes().contains("after-failover")).isTrue();
        assertThat(fixture.client().state().nodes().contains("meta-safe")).isTrue();
        fixture.close();
    }

    @Test
    void writesAppliedInRaftOrder() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.client().join("a");
            fixture.client().join("b");
            fixture.client().join("c");
            List<String> nodes = new ArrayList<>(
                    fixture.client().state().nodes().nodes());
            assertThat(nodes).containsExactlyInAnyOrder("a", "b", "c");
        }
    }

    @Test
    void migrationStatusThroughGroup() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.client().migrationStatus(2, "COPYING");
            fixture.client().migrationStatus(2, "DONE");
            assertThat(fixture.client().state().migrationStatus(2)).isEqualTo("DONE");
        }
    }

    @Test
    void assignSlotsAffectsRouting() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.client().assignSlots(2);
            assertThat(fixture.client().state().topology().slotTable().shardFor(0)).isZero();
            assertThat(fixture.client().state().topology().slotTable().shardFor(1)).isEqualTo(1);
        }
    }

    @Test
    void noLeaderWriteFails() throws Exception {
        Fixture fixture = fixture();
        for (RaftNode node : fixture.group().nodes()) {
            node.suspend();
            node.close();
        }
        assertThatThrownBy(() -> fixture.client().join("x"))
                .isInstanceOf(IllegalStateException.class);
        fixture.close();
    }

    @Test
    void concurrentWritesAllApplied() throws Exception {
        try (Fixture fixture = fixture()) {
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int index = i;
                Thread thread = new Thread(() -> fixture.client().join("node-" + index));
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(5000);
            }
            assertThat(fixture.client().state().nodes().size()).isEqualTo(10);
        }
    }

    @Test
    void metadataSnapshotOfRegistryAndTopology() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.client().join("n1");
            fixture.client().createShard(new ShardId(0), List.of("n1"), "n1");
            ClusterMetadata metadata = new ClusterMetadata(
                    fixture.client().state().nodes().nodes().stream().toList(),
                    fixture.client().state().topology().shardRegistry().all());
            assertThat(metadata.nodes()).contains("n1");
            assertThat(metadata.shards()).hasSize(1);
        }
    }

    private static Fixture fixture() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.create(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10);
        group.start();
        awaitLeader(group.nodes(), 5000);
        return new Fixture(group, new MetadataClient(group));
    }

    private record Fixture(MetadataRaftGroup group, MetadataClient client)
            implements AutoCloseable {
        @Override
        public void close() {
            group.close();
        }
    }
}
