package io.tieringkv.txn.meta;

import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 元数据多 Raft（ADR-0095）：3 节点决策高可用。 */
class MetadataMultiRaftTest {

    @TempDir
    Path dir;

    private TxnMetadataClient client;
    private List<TxnMetadataNode> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (TxnMetadataNode node : nodes) {
            node.raft().close();
        }
    }

    private void start(int count) throws Exception {
        nodes = new ArrayList<>();
        List<io.tieringkv.cluster.raft.RaftNode> peers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TxnMetadataNode node = new TxnMetadataNode("meta-" + i, peers);
            nodes.add(node);
            peers.add(node.raft());
        }
        for (TxnMetadataNode node : nodes) {
            node.raft().start();
        }
        client = new TxnMetadataClient(nodes);
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                client.leader();
                return;
            } catch (IllegalStateException ignored) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("no metadata leader");
    }

    @Test
    void electsSingleLeader() throws Exception {
        start(3);
        int leaders = 0;
        for (TxnMetadataNode node : nodes) {
            if (node.raft().state() == io.tieringkv.cluster.raft.RaftState
                    .LEADER) {
                leaders++;
            }
        }
        assertThat(leaders).isEqualTo(1);
    }

    @Test
    void proposeAppliesOnAllNodes() throws Exception {
        start(3);
        TxnMetaCommand command = TxnMetaCommand.register(
                "t1", new byte[]{1}, 1,
                Map.of("r1", List.of()));
        client.proposer().apply(TxnMetaCodec.encode(command)).join();
        Thread.sleep(200);
        for (TxnMetadataNode node : nodes) {
            assertThat(node.state().get("t1")).isNotNull();
        }
    }

    @Test
    void leaderKillNewLeaderProposes() throws Exception {
        start(3);
        TxnMetadataNode first = client.leader();
        first.raft().suspend();
        first.raft().close();
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                TxnMetadataNode leader = client.leader();
                if (!leader.equals(first)) {
                    TxnMetaCommand command = TxnMetaCommand.register(
                            "t2", new byte[]{2}, 2,
                            Map.of("r1", List.of()));
                    client.proposer().apply(
                            TxnMetaCodec.encode(command)).join();
                    Thread.sleep(200);
                    int applied = 0;
                    for (TxnMetadataNode node : nodes) {
                        if (node.state().get("t2") != null) {
                            applied++;
                        }
                    }
                    assertThat(applied).isGreaterThanOrEqualTo(2);
                    return;
                }
            } catch (IllegalStateException ignored) {
                // 选举中
            }
            Thread.sleep(10);
        }
        throw new AssertionError("failover did not complete");
    }

    @Test
    void snapshotRoundTrip() throws Exception {
        start(1);
        TxnMetaCommand command = TxnMetaCommand.register(
                "t1", new byte[]{1}, 1,
                Map.of("r1", List.of()));
        client.proposer().apply(TxnMetaCodec.encode(command)).join();
        Path file = dir.resolve("snap.bin");
        MetadataSnapshotManager.snapshot(file, nodes.get(0).state());
        io.tieringkv.transaction.metadata.TransactionMetadataState loaded =
                MetadataSnapshotManager.load(file);
        assertThat(loaded.get("t1")).isNotNull();
    }

    @Test
    void snapshotTruncatedTailTolerated() throws Exception {
        Path file = dir.resolve("trunc.bin");
        java.nio.file.Files.write(file, new byte[]{0, 0, 0, 5, 1, 2, 3});
        assertThat(MetadataSnapshotManager.load(file).size()).isZero();
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedNodeCount(int count) throws Exception {
        start(count);
        TxnMetaCommand command = TxnMetaCommand.register(
                "t1", new byte[]{1}, 1,
                Map.of("r1", List.of()));
        client.proposer().apply(TxnMetaCodec.encode(command)).join();
        Thread.sleep(200);
        assertThat(client.leader()).isNotNull();
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50, 100})
    void parameterizedProposals(int count) throws Exception {
        start(3);
        for (int i = 0; i < count; i++) {
            TxnMetaCommand command = TxnMetaCommand.register(
                    "t" + i, new byte[]{(byte) i}, i,
                    Map.of("r1", List.of()));
            client.proposer().apply(TxnMetaCodec.encode(command)).join();
        }
        Thread.sleep(200);
        assertThat(client.leader().state().size())
                .isGreaterThanOrEqualTo(count);
    }
}
