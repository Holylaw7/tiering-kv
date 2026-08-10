package io.tieringkv.cluster;

import io.tieringkv.cluster.metadata.ClusterMetadata;
import io.tieringkv.cluster.metadata.MetadataServer;
import io.tieringkv.cluster.sharding.ShardGroup;
import io.tieringkv.cluster.sharding.ShardId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataTest {

    private MetadataServer server;

    @BeforeEach
    void setUp() {
        server = new MetadataServer();
    }

    @Test
    void joinRegistersNode() {
        assertThat(server.join("n1", 1)).isTrue();
        assertThat(server.nodes().contains("n1")).isTrue();
    }

    @Test
    void duplicateJoinReturnsFalse() {
        server.join("n1", 1);
        assertThat(server.join("n1", 1)).isFalse();
    }

    @Test
    void leaveRemovesNode() {
        server.join("n1", 1);
        assertThat(server.leave("n1")).isTrue();
        assertThat(server.nodes().contains("n1")).isFalse();
    }

    @Test
    void firstJoinAssignsSlots() {
        server.join("n1", 2);
        assertThat(server.topology().slotTable().shardFor(0)).isZero();
        assertThat(server.topology().slotTable().shardFor(16_383)).isEqualTo(1);
    }

    @Test
    void createShardStoresGroup() {
        server.createShard(new ShardId(0), List.of("n1", "n2", "n3"), "n1");
        ShardGroup group = server.topology().shardRegistry().get(0);
        assertThat(group.nodes()).hasSize(3);
        assertThat(group.leader()).isEqualTo("n1");
    }

    @Test
    void updateLeaderChangesLeader() {
        server.createShard(new ShardId(0), List.of("n1", "n2"), "n1");
        server.updateLeader(0, "n2");
        assertThat(server.topology().shardRegistry().get(0).leader()).isEqualTo("n2");
    }

    @Test
    void topologyLeaderForRoutesByKey() {
        server.join("n1", 2);
        server.join("n2", 2);
        server.createShard(new ShardId(0), List.of("n1", "n2"), "n1");
        server.createShard(new ShardId(1), List.of("n1", "n2"), "n2");
        assertThat(server.topology().leaderFor("k".getBytes(StandardCharsets.UTF_8)))
                .isIn("n1", "n2");
    }

    @Test
    void topologyShardForByKey() {
        server.join("n1", 3);
        assertThat(server.topology().shardFor("k".getBytes(StandardCharsets.UTF_8)))
                .isBetween(0, 2);
    }

    @Test
    void metadataSnapshotContainsNodesAndShards() {
        server.join("n1", 1);
        server.createShard(new ShardId(0), List.of("n1"), "n1");
        ClusterMetadata metadata = server.metadata();
        assertThat(metadata.nodes()).contains("n1");
        assertThat(metadata.shards()).hasSize(1);
    }

    @Test
    void leaveUpdatesGroupMembershipAndClearsLeader() {
        server.createShard(new ShardId(0), List.of("n1", "n2"), "n1");
        server.leave("n1");
        ShardGroup group = server.topology().shardRegistry().get(0);
        assertThat(group.nodes()).containsExactly("n2");
        assertThat(group.leader()).isNull();
    }
}
