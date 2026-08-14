package io.tieringkv.benchmark.replication;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.replication.cross.CrossClusterReplicationChannel;
import io.tieringkv.replication.cross.CrossClusterSink;
import io.tieringkv.replication.cross.LwwConflictResolver;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 跨集群复制基准（ADR-0321）：RPC 通道事件发送吞吐。
 */
@Tag("benchmark")
class CrossClusterReplicationBenchmarkTest {

    @Test
    void replicationThroughput() throws Exception {
        int portA = io.tieringkv.testkit.TestPorts.freePort();
        int portB = io.tieringkv.testkit.TestPorts.freePort();
        Map<String, InetSocketAddress> addresses = Map.of(
                "a", new InetSocketAddress("127.0.0.1", portA),
                "b", new InetSocketAddress("127.0.0.1", portB));
        MultiRaftEndpoint endpointA =
                new MultiRaftEndpoint("a", portA, addresses);
        MultiRaftEndpoint endpointB =
                new MultiRaftEndpoint("b", portB, addresses);
        endpointA.start();
        endpointB.start();
        try {
            MemTable storageB = MemTable.create();
            CrossClusterSink sink = new CrossClusterSink(storageB,
                    new LwwConflictResolver());
            CrossClusterReplicationChannel receiver =
                    new CrossClusterReplicationChannel(endpointB, "a");
            receiver.registerConsumer(event -> sink.apply(event,
                    "cluster-a"));
            CrossClusterReplicationChannel sender =
                    new CrossClusterReplicationChannel(endpointA, "b");

            // 预热
            sender.send(event(0, "warm", "x")).get(5, TimeUnit.SECONDS);
            int rounds = 5_000;
            long t0 = System.nanoTime();
            for (int i = 1; i <= rounds; i++) {
                sender.send(event(i, "k" + (i % 100),
                        "value-" + i)).get(5, TimeUnit.SECONDS);
            }
            double seconds = (System.nanoTime() - t0)
                    / 1_000_000_000.0;
            double ops = rounds / seconds;
            System.out.printf(Locale.ROOT,
                    "PHASE60-BENCH CROSS-CLUSTER REPLICATION "
                            + "events=%d ops/s=%.0f%n",
                    rounds, ops);
        } finally {
            endpointB.close();
            endpointA.close();
        }
    }

    private static ChangeEvent event(long seq, String key,
                                     String value) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8), false,
                "t" + seq, "r1", seq);
    }
}
