package io.tieringkv.cluster;

import io.tieringkv.cluster.metadata.MetadataServer;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.sharding.ShardId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.tieringkv.cluster.RaftTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class FailoverTest {

    @Test
    void leaderCrashElectsNewLeaderUnderFiveSeconds() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        long start = System.currentTimeMillis();
        leader.suspend();
        leader.close();
        awaitTrue("new leader", () -> {
            for (RaftNode node : nodes) {
                if (node != leader && node.state() == RaftState.LEADER) {
                    return true;
                }
            }
            return false;
        }, 5000);
        long electionMs = System.currentTimeMillis() - start;
        assertThat(electionMs).isLessThan(5000);
        closeAll(nodes);
    }

    @Test
    void writesWorkAfterFailover() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        leader.propose("before".getBytes(StandardCharsets.UTF_8)).get();
        leader.suspend();
        leader.close();
        RaftNode newLeader = null;
        for (int i = 0; i < 500; i++) {
            for (RaftNode node : nodes) {
                if (node != leader && node.state() == RaftState.LEADER) {
                    newLeader = node;
                    break;
                }
            }
            if (newLeader != null) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(newLeader).isNotNull();
        newLeader.propose("after".getBytes(StandardCharsets.UTF_8)).get();
        assertThat(newLeader.commitIndex()).isEqualTo(1);
        closeAll(nodes);
    }

    @Test
    void committedValueSurvivesLeaderCrash() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        leader.propose("persist".getBytes(StandardCharsets.UTF_8)).get();
        leader.suspend();
        leader.close();
        RaftNode survivor = java.util.Arrays.stream(nodes)
                .filter(node -> node != leader).findFirst().orElseThrow();
        awaitTrue("applied on survivor", () -> applied.size() >= 1, 5000);
        awaitTrue("survivor log caught up", () ->
                !survivor.logSnapshot().isEmpty()
                        && java.util.Arrays.equals(
                        survivor.logSnapshot().get(0).command(),
                        "persist".getBytes(StandardCharsets.UTF_8)), 5000);
        closeAll(nodes);
    }

    @Test
    void replicaCrashClusterContinues() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        for (RaftNode node : nodes) {
            if (node != leader) {
                node.suspend();
                node.close();
                break;
            }
        }
        leader.propose("ok".getBytes(StandardCharsets.UTF_8)).get();
        // 这是集群首条日志（0 基索引），半数存活时提交后 commitIndex = 0
        assertThat(leader.commitIndex()).isEqualTo(0);
        closeAll(nodes);
    }

    @Test
    void metadataLeaderUpdatedAfterFailover() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        MetadataServer metadata = new MetadataServer();
        metadata.join("n1", 1);
        metadata.createShard(new ShardId(0), List.of("n1", "n2", "n3"), leader.id());
        leader.suspend();
        leader.close();
        RaftNode newLeader = awaitLeader(List.of(nodes), 5000);
        metadata.updateLeader(0, newLeader.id());
        assertThat(metadata.topology().shardRegistry().get(0).leader())
                .isEqualTo(newLeader.id());
        closeAll(nodes);
    }

    @Test
    void twoOfThreeClusterSurvivesSingleFailure() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        for (RaftNode node : nodes) {
            if (node != leader) {
                node.suspend();
                break; // 仅模拟单个 follower 故障
            }
        }
        leader.propose("majority".getBytes(StandardCharsets.UTF_8)).get();
        assertThat(leader.commitIndex()).isEqualTo(0);
        closeAll(nodes);
    }

    @Test
    void singleSurvivorCannotCommit() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        for (RaftNode node : nodes) {
            if (node != leader) {
                node.suspend();
                node.close();
            }
        }
        leader.propose("no-majority".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(300);
        assertThat(leader.commitIndex()).isEqualTo(-1);
        closeAll(nodes);
    }

    @Test
    void committedLogConsistentAcrossReplicas() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        for (int i = 0; i < 3; i++) {
            leader.propose(("v" + i).getBytes(StandardCharsets.UTF_8)).get();
        }
        awaitTrue("replicas catch up", () -> {
            for (RaftNode node : nodes) {
                if (node.logSize() != 3) {
                    return false;
                }
            }
            return true;
        }, 5000);
        for (RaftNode node : nodes) {
            assertThat(node.logSnapshot().get(2).command())
                    .isEqualTo("v2".getBytes(StandardCharsets.UTF_8));
        }
        closeAll(nodes);
    }

    @Test
    void oldLeaderRejoinIsSafelyIgnored() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        RaftNode leader = awaitLeader(List.of(nodes), 5000);
        long oldTerm = leader.currentTerm();
        leader.suspend();
        leader.close();
        RaftNode newLeader = awaitLeader(List.of(nodes), 5000);
        // 旧 leader 恢复后收到更高 term 的心跳会降级为 follower
        leader.resume();
        leader.receive(new io.tieringkv.cluster.raft.AppendEntriesRequest(
                newLeader.currentTerm(), newLeader.id(), -1, 0, List.of(), 0));
        assertThat(leader.state()).isEqualTo(RaftState.FOLLOWER);
        assertThat(leader.currentTerm()).isGreaterThanOrEqualTo(oldTerm);
        closeAll(nodes);
    }
}
