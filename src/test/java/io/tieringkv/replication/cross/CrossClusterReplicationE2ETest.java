package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.distributed.FederationConsistencyVerifier;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨集群复制 E2E（ADR-0321）：双 endpoint RPC 通道，单写一致 / 双写
 * LWW 收敛 / 重复幂等 / 一致性验证接线。
 */
class CrossClusterReplicationE2ETest {

    private MultiRaftEndpoint endpointA;
    private MultiRaftEndpoint endpointB;

    @AfterEach
    void tearDown() {
        if (endpointB != null) {
            endpointB.close();
        }
        if (endpointA != null) {
            endpointA.close();
        }
    }

    private void startEndpoints() throws Exception {
        int portA = io.tieringkv.testkit.TestPorts.freePort();
        int portB = io.tieringkv.testkit.TestPorts.freePort();
        Map<String, InetSocketAddress> addresses = Map.of(
                "a", new InetSocketAddress("127.0.0.1", portA),
                "b", new InetSocketAddress("127.0.0.1", portB));
        endpointA = new MultiRaftEndpoint("a", portA, addresses);
        endpointB = new MultiRaftEndpoint("b", portB, addresses);
        endpointA.start();
        endpointB.start();
    }

    private static ChangeEvent put(long seq, String key, String value,
                                   long timestamp) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8), false,
                "t" + seq, "r1", timestamp);
    }

    @Test
    void singleWriteReplicatesAndApplies() throws Exception {
        startEndpoints();
        MemTable storageB = MemTable.create();
        CrossClusterSink sink = new CrossClusterSink(storageB,
                new LwwConflictResolver());
        CrossClusterReplicationChannel receiver =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiver.registerConsumer(event -> sink.apply(event,
                "cluster-a"));

        CrossClusterReplicationChannel sender =
                new CrossClusterReplicationChannel(endpointA, "b");
        assertThat(sender.send(put(1, "k", "v1", 100))
                .get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(storageB.get(
                "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void concurrentWritesConvergeByLww() throws Exception {
        startEndpoints();
        MemTable storageB = MemTable.create();
        CrossClusterSink sink = new CrossClusterSink(storageB,
                new LwwConflictResolver());
        CrossClusterReplicationChannel receiver =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiver.registerConsumer(event -> sink.apply(event,
                event.timestamp() % 2 == 0 ? "cluster-a"
                        : "cluster-b"));

        CrossClusterReplicationChannel sender =
                new CrossClusterReplicationChannel(endpointA, "b");
        sender.send(put(1, "k", "old", 100))
                .get(5, TimeUnit.SECONDS);
        sender.send(put(2, "k", "new", 200))
                .get(5, TimeUnit.SECONDS);
        sender.send(put(3, "k", "stale", 150))
                .get(5, TimeUnit.SECONDS);
        assertThat(storageB.get(
                "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("new".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void duplicateEventsAreIdempotent() throws Exception {
        startEndpoints();
        MemTable storageB = MemTable.create();
        CrossClusterSink sink = new CrossClusterSink(storageB,
                new LwwConflictResolver());
        CrossClusterReplicationChannel receiver =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiver.registerConsumer(event -> sink.apply(event,
                "cluster-a"));

        CrossClusterReplicationChannel sender =
                new CrossClusterReplicationChannel(endpointA, "b");
        ChangeEvent event = put(1, "k", "v", 100);
        sender.send(event).get(5, TimeUnit.SECONDS);
        sender.send(event).get(5, TimeUnit.SECONDS);
        assertThat(storageB.get(
                "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void federationVerifierMirrorsReplicatedState() throws Exception {
        startEndpoints();
        MemTable storageB = MemTable.create();
        CrossClusterSink sink = new CrossClusterSink(storageB,
                new LwwConflictResolver());
        CrossClusterReplicationChannel receiver =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiver.registerConsumer(event -> sink.apply(event,
                "cluster-a"));
        CrossClusterReplicationChannel sender =
                new CrossClusterReplicationChannel(endpointA, "b");

        FederationConsistencyVerifier verifier =
                new FederationConsistencyVerifier("cluster-a",
                        "cluster-b");
        ChangeEvent event = put(1, "k", "v", 100);
        verifier.write("cluster-a", "k", "v");
        sender.send(event).get(5, TimeUnit.SECONDS);
        verifier.sync("cluster-a", "cluster-b", "k");

        assertThat(storageB.get(
                "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.conflictRate()).isZero();
        assertThat(verifier.syncs()).isEqualTo(1);
    }
}
