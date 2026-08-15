package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复制流水线增强（ADR-0333）：批量帧按序应用、单/批量兼容、
 * 异步 ack metrics 计数。
 */
class CrossClusterReplicationChannelBatchTest {

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

    private static ChangeEvent event(long seq, String key,
                                     String value) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8), false,
                "t" + seq, "r1", seq);
    }

    @Test
    void batchEventsApplyInOrder() throws Exception {
        startEndpoints();
        MemTable storageB = MemTable.create();
        List<String> appliedOrder = new ArrayList<>();
        CrossClusterReplicationChannel receiver =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiver.registerConsumer(event -> {
            appliedOrder.add(new String(event.key(),
                    StandardCharsets.UTF_8));
            new CrossClusterSink(storageB, new LwwConflictResolver())
                    .apply(event, "cluster-a");
        });

        List<ChangeEvent> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(event(i, "k" + i, "v" + i));
        }
        CrossClusterReplicationChannel sender =
                new CrossClusterReplicationChannel(endpointA, "b");
        assertThat(sender.sendBatch(batch)
                .get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(appliedOrder).hasSize(100);
        assertThat(appliedOrder.get(0)).isEqualTo("k0");
        assertThat(appliedOrder.get(99)).isEqualTo("k99");
        assertThat(storageB.get("k99".getBytes(
                StandardCharsets.UTF_8)))
                .isEqualTo("v99".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void batchAndSingleEventsInteroperate() throws Exception {
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
        assertThat(sender.sendBatch(List.of(
                event(1, "k1", "v1"),
                event(2, "k2", "v2"))).get(5, TimeUnit.SECONDS))
                .isTrue();
        assertThat(sender.send(event(3, "k3", "v3"))
                .get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(storageB.get("k1".getBytes(
                StandardCharsets.UTF_8)))
                .isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
        assertThat(storageB.get("k3".getBytes(
                StandardCharsets.UTF_8)))
                .isEqualTo("v3".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sendAsyncCountsSuccessMetrics() throws Exception {
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
        for (int i = 0; i < 50; i++) {
            sender.sendAsync(event(i, "ak" + i, "av" + i));
        }
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (sender.successCount() < 50
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(sender.successCount()).isEqualTo(50);
        assertThat(sender.failureCount()).isZero();
        assertThat(storageB.get("ak49".getBytes(
                StandardCharsets.UTF_8)))
                .isEqualTo("av49".getBytes(StandardCharsets.UTF_8));
    }
}
