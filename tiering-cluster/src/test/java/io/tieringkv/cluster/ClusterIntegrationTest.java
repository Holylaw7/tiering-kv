package io.tieringkv.cluster;

import io.tieringkv.cluster.metadata.MetadataServer;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.sharding.ShardId;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

/** 3 节点集群集成：SET → 复制 → 杀 leader → 选举 → GET 正确。 */
class ClusterIntegrationTest {

    @Test
    void threeNodeClusterSurvivesLeaderCrash() throws Exception {
        MetadataServer metadata = new MetadataServer();
        metadata.join("n1", 1);
        metadata.join("n2", 1);
        metadata.join("n3", 1);
        metadata.createShard(new ShardId(0), List.of("n1", "n2", "n3"), null);

        LeaderElection election = new LeaderElection(100, 80);
        List<io.tieringkv.cluster.raft.RaftNode> peers = new ArrayList<>();
        ClusterNode n1 = ClusterNode.create("n1", peers, MemTable.create(), election, 25, 10);
        ClusterNode n2 = ClusterNode.create("n2", peers, MemTable.create(), election, 25, 10);
        ClusterNode n3 = ClusterNode.create("n3", peers, MemTable.create(), election, 25, 10);
        peers.add(n1.raft());
        peers.add(n2.raft());
        peers.add(n3.raft());
        n1.start();
        n2.start();
        n3.start();
        Map<String, ClusterNode> nodes = Map.of("n1", n1, "n2", n2, "n3", n3);
        ClusterClient client = new ClusterClient(metadata, nodes);
        try {
            ClusterNode leader = awaitClusterLeader(nodes);
            metadata.updateLeader(0, leader.id());

            // 1. 写入 + 2. 复制
            client.put("user:1".getBytes(StandardCharsets.UTF_8), "value".getBytes(StandardCharsets.UTF_8));
            awaitTrue("replicated", () ->
                    n1.get("user:1".getBytes(StandardCharsets.UTF_8)) != null
                            && n2.get("user:1".getBytes(StandardCharsets.UTF_8)) != null
                            && n3.get("user:1".getBytes(StandardCharsets.UTF_8)) != null, 5000);

            // 3. kill leader
            leader.raft().suspend();
            leader.close();

            // 4. 新 leader 选举
            ClusterNode newLeader = awaitClusterLeader(nodes);
            assertThat(newLeader.id()).isNotEqualTo(leader.id());
            metadata.updateLeader(0, newLeader.id());

            // 5. GET 返回正确值（数据已复制/提交）
            assertThat(client.get("user:1".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo("value".getBytes(StandardCharsets.UTF_8));
        } finally {
            n1.close();
            n2.close();
            n3.close();
        }
    }

    private static ClusterNode awaitClusterLeader(Map<String, ClusterNode> nodes)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (ClusterNode node : nodes.values()) {
                if (node.raft().state() == RaftState.LEADER) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no cluster leader elected");
    }
}
