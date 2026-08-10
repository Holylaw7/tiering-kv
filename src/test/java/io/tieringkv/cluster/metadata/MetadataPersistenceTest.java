package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.sharding.ShardId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static org.assertj.core.api.Assertions.assertThat;

/** 元数据 Raft 持久化（ADR-0052）：快照编解码 / 重启恢复 / 拓扑保留。 */
class MetadataPersistenceTest {

    @TempDir
    Path dir;

    @Test
    void codecRoundTripNodes() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.join("n1"));
        state.apply(MetadataCodec.join("n2"));
        MetadataState restored = new MetadataState();
        MetadataStateCodec.restore(restored, MetadataStateCodec.serialize(state));
        assertThat(restored.nodes().nodes()).containsExactlyInAnyOrder("n1", "n2");
    }

    @Test
    void codecRoundTripSlots() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.assignSlots(3));
        MetadataState restored = new MetadataState();
        MetadataStateCodec.restore(restored, MetadataStateCodec.serialize(state));
        assertThat(restored.topology().slotTable().shardFor(16383)).isZero();
        assertThat(restored.topology().slotTable().shardFor(100)).isEqualTo(1);
    }

    @Test
    void codecRoundTripShards() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.createShard(new ShardId(0),
                List.of("n1", "n2"), "n1"));
        MetadataState restored = new MetadataState();
        MetadataStateCodec.restore(restored, MetadataStateCodec.serialize(state));
        assertThat(restored.topology().shardRegistry().get(0).leader()).isEqualTo("n1");
        assertThat(restored.topology().shardRegistry().get(0).nodes())
                .containsExactly("n1", "n2");
    }

    @Test
    void codecRoundTripMigrationStatus() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.migrationStatus(2, "DONE"));
        MetadataState restored = new MetadataState();
        MetadataStateCodec.restore(restored, MetadataStateCodec.serialize(state));
        assertThat(restored.migrationStatus(2)).isEqualTo("DONE");
    }

    @Test
    void codecRoundTripEmptyState() {
        MetadataState state = new MetadataState();
        MetadataState restored = new MetadataState();
        MetadataStateCodec.restore(restored, MetadataStateCodec.serialize(state));
        assertThat(restored.nodes().size()).isZero();
    }

    @Test
    void persistentGroupRestartPreservesTopology() throws Exception {
        MetadataRaftGroup first = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        first.start();
        MetadataClient client = new MetadataClient(first);
        awaitLeader(first.nodes(), 5000);
        client.join("storage-1");
        client.assignSlots(2);
        client.createShard(new ShardId(0), List.of("storage-1"), "storage-1");
        client.migrationStatus(0, "SWITCHING");
        first.close();

        MetadataRaftGroup second = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        second.start();
        try {
            RaftNode leader = awaitLeader(second.nodes(), 5000);
            MetadataState state = second.state(leader.id());
            assertThat(state.nodes().contains("storage-1")).isTrue();
            assertThat(state.topology().slotTable().shardFor(100)).isEqualTo(0);
            assertThat(state.topology().shardRegistry().get(0).leader())
                    .isEqualTo("storage-1");
            assertThat(state.migrationStatus(0)).isEqualTo("SWITCHING");
        } finally {
            second.close();
        }
    }

    @Test
    void leaderRestartPreservesTopology() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        group.start();
        MetadataClient client = new MetadataClient(group);
        RaftNode leader = awaitLeader(group.nodes(), 5000);
        client.join("storage-9");
        String leaderId = leader.id();
        leader.suspend();
        leader.close();
        RaftNode newLeader = awaitLeader(group.nodes(), 5000);
        assertThat(newLeader.id()).isNotEqualTo(leaderId);
        client.join("storage-10");
        assertThat(client.state().nodes().contains("storage-9")).isTrue();
        assertThat(client.state().nodes().contains("storage-10")).isTrue();
        group.close();
    }

    @Test
    void termPersistedAcrossRestart() throws Exception {
        MetadataRaftGroup first = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        first.start();
        RaftNode leader = awaitLeader(first.nodes(), 5000);
        long term = leader.currentTerm();
        first.close();

        MetadataRaftGroup second = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        second.start();
        try {
            RaftNode newLeader = awaitLeader(second.nodes(), 5000);
            assertThat(newLeader.currentTerm()).isGreaterThanOrEqualTo(term);
        } finally {
            second.close();
        }
    }

    @Test
    void snapshotCompactionKeepsState() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        group.start();
        MetadataClient client = new MetadataClient(group);
        try {
            awaitLeader(group.nodes(), 5000);
            for (int i = 0; i < 1100; i++) {
                client.migrationStatus(i % 8, "S" + i);
            }
            assertThat(client.state().migrationStatus(3)).isEqualTo("S1099");
        } finally {
            group.close();
        }
    }

    @Test
    void corruptedSnapshotFallsBackToLog() throws Exception {
        MetadataRaftGroup first = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        first.start();
        MetadataClient client = new MetadataClient(first);
        awaitLeader(first.nodes(), 5000);
        client.join("survivor-key");
        first.close();
        // 破坏 m1 快照文件
        Path snapshot = dir.resolve("m1").resolve("snapshot").resolve("snapshot.latest");
        if (Files.exists(snapshot)) {
            byte[] bytes = Files.readAllBytes(snapshot);
            bytes[bytes.length - 1] ^= 0x01;
            Files.write(snapshot, bytes);
        }
        MetadataRaftGroup second = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        second.start();
        try {
            RaftNode leader = awaitLeader(second.nodes(), 5000);
            assertThat(second.state(leader.id()).nodes().contains("survivor-key")).isTrue();
        } finally {
            second.close();
        }
    }

    @Test
    void allReplicasConsistentAfterRestart() throws Exception {
        MetadataRaftGroup first = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        first.start();
        MetadataClient client = new MetadataClient(first);
        awaitLeader(first.nodes(), 5000);
        client.join("consistent");
        first.close();

        MetadataRaftGroup second = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        second.start();
        try {
            RaftNode leader = awaitLeader(second.nodes(), 5000);
            // Raft：新 leader 提交旧 term 条目前需先追加自己的条目
            new MetadataClient(second).join("after");
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                boolean all = true;
                for (RaftNode node : second.nodes()) {
                    if (!second.state(node.id()).nodes().contains("consistent")
                            || !second.state(node.id()).nodes().contains("after")) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    break;
                }
                Thread.sleep(20);
            }
            for (RaftNode node : second.nodes()) {
                assertThat(second.state(node.id()).nodes().contains("consistent")).isTrue();
                assertThat(second.state(node.id()).nodes().contains("after")).isTrue();
            }
        } finally {
            second.close();
        }
    }

    @Test
    void writesAfterRestartContinue() throws Exception {
        MetadataRaftGroup first = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        first.start();
        MetadataClient client = new MetadataClient(first);
        awaitLeader(first.nodes(), 5000);
        client.join("before-restart");
        first.close();

        MetadataRaftGroup second = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        second.start();
        MetadataClient secondClient = new MetadataClient(second);
        try {
            awaitLeader(second.nodes(), 5000);
            secondClient.join("after-restart");
            assertThat(secondClient.state().nodes().contains("before-restart")).isTrue();
            assertThat(secondClient.state().nodes().contains("after-restart")).isTrue();
        } finally {
            second.close();
        }
    }

    @Test
    void codecHandlesNullLeader() {
        MetadataState state = new MetadataState();
        state.apply(MetadataCodec.createShard(new ShardId(0), List.of("n1"), null));
        MetadataState restored = new MetadataState();
        MetadataStateCodec.restore(restored, MetadataStateCodec.serialize(state));
        assertThat(restored.topology().shardRegistry().get(0).leader()).isNull();
    }

    @Test
    void snapshotFileCreatedAfterWrites() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10, dir);
        group.start();
        MetadataClient client = new MetadataClient(group);
        try {
            awaitLeader(group.nodes(), 5000);
            for (int i = 0; i < 1100; i++) {
                client.migrationStatus(i % 4, "M" + i);
            }
            boolean found = Files.exists(dir.resolve("m1").resolve("snapshot")
                    .resolve("snapshot.latest"))
                    || Files.exists(dir.resolve("m2").resolve("snapshot")
                    .resolve("snapshot.latest"))
                    || Files.exists(dir.resolve("m3").resolve("snapshot")
                    .resolve("snapshot.latest"));
            assertThat(found).isTrue();
        } finally {
            group.close();
        }
    }
}
