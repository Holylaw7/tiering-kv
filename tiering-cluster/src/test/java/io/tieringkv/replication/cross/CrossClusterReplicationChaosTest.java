package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨集群复制分区/恢复混沌（ADR-0321 M3 收尾）：分区失败 → 恢复重放
 * → 幂等一致。 */
class CrossClusterReplicationChaosTest {

    private MultiRaftEndpoint endpointA;
    private MultiRaftEndpoint endpointB;

    @TempDir
    Path dir;

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

    private static ChangeEvent put(long seq, String key, String value) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8), false,
                "t" + seq, "r1", seq);
    }

    @Test
    void partitionThenRecoveryReplaysIdempotently() throws Exception {
        startEndpoints();
        MemTable storageB = MemTable.create();
        CrossClusterWatermark watermark = new CrossClusterWatermark(
                dir.resolve("wm.bin"));
        CrossClusterSink sink = new CrossClusterSink(storageB,
                new LwwConflictResolver(), watermark);
        AtomicBoolean partitioned = new AtomicBoolean(true);
        CrossClusterReplicationChannel receiver =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiver.registerConsumer(event -> {
            if (partitioned.get()) {
                throw new IllegalStateException("partition");
            }
            sink.apply(event, "cluster-a");
        });
        CrossClusterReplicationChannel sender =
                new CrossClusterReplicationChannel(endpointA, "b");

        // 分区窗口：发送失败（目标端拒绝）
        List<ChangeEvent> pending = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ChangeEvent event = put(i, "k" + i, "v" + i);
            boolean accepted;
            try {
                accepted = sender.send(event)
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                accepted = false;
            }
            if (!accepted) {
                pending.add(event);
            }
        }
        assertThat(storageB.size()).isZero();
        assertThat(pending).isNotEmpty();

        // 恢复：重放全部待发事件（含可能已投递的，幂等水位保证）
        partitioned.set(false);
        for (ChangeEvent event : pending) {
            sender.send(event).get(5, TimeUnit.SECONDS);
        }
        assertThat(storageB.size()).isEqualTo(10);
        assertThat(storageB.get(
                "k10".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v10".getBytes(StandardCharsets.UTF_8));

        // 再次重放（重复投递）不改变状态
        for (ChangeEvent event : pending) {
            sender.send(event).get(5, TimeUnit.SECONDS);
        }
        assertThat(storageB.size()).isEqualTo(10);
        watermark.close();
    }

    @Test
    void pipelineForwardsToRemoteCluster() throws Exception {
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

        io.tieringkv.replication.ReplicationPipeline pipeline =
                new io.tieringkv.replication.ReplicationPipeline(
                        List.of(new CrossClusterReplicaSink(
                                "cluster-b", sender)),
                        io.tieringkv.replication.ReplicationMode.SYNC,
                        2_000, "r1");
        assertThat(pipeline.replicate(put(1, "k", "v"))
                .get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(storageB.get(
                "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v".getBytes(StandardCharsets.UTF_8));
    }
}
