package io.tieringkv.cluster.gateway;

import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.routing.RoutingTable;
import io.tieringkv.cluster.routing.RoutingTableEntry;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.network.TestRespClient;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 TCP Redis Cluster 网关集成（ADR-0068）：socket/pipeline/并发/重定向。 */
class GatewayIntegrationTest {

    private RoutingTable table;
    private MemTable n1;
    private NettyClusterGateway server;
    private byte[] localKey;
    private byte[] remoteKey;

    @BeforeEach
    void setUp() throws Exception {
        table = new RoutingTable();
        n1 = MemTable.create();
        MemTable n2 = MemTable.create();
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                RegionEpoch.INITIAL, "n1", "g1", false));
        table.update(entry(new RegionId(2), "m", "z", 8192, 16383,
                RegionEpoch.INITIAL, "n2", "g2", false));
        Map<String, MemTable> storages = Map.of("n1", n1, "n2", n2);
        Map<String, InetSocketAddress> addresses = Map.of(
                "n1", new InetSocketAddress("127.0.0.1", 7001),
                "n2", new InetSocketAddress("127.0.0.1", 7002),
                "n3", new InetSocketAddress("127.0.0.1", 7003));
        UnifiedClusterGateway gateway = new UnifiedClusterGateway(
                table, Map.copyOf(storages), addresses, "n1");
        server = new NettyClusterGateway(gateway);
        server.start("127.0.0.1", 0);
        localKey = keyIn("key:", 0, 8191, "a", "m");
        remoteKey = keyIn("my:key:", 8192, 16383, "m", "z");
    }

    @AfterEach
    void tearDown() {
        server.close();
        n1.close();
    }

    @Test
    void setThenGetLocal() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("SET", str(localKey), "v1"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).isEqualTo("$2\r\nv1\r\n");
        }
    }

    @Test
    void getMissingReturnsNull() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).isEqualTo("$-1");
        }
    }

    @Test
    void delLocal() throws Exception {
        n1.put(localKey, bytes("v"));
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("DEL", str(localKey)));
            assertThat(client.readResponse()).isEqualTo(":1");
            assertThat(n1.get(localKey)).isNull();
        }
    }

    @Test
    void delMissingReturnsZero() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("DEL", str(localKey)));
            assertThat(client.readResponse()).isEqualTo(":0");
        }
    }

    @Test
    void mgetLocal() throws Exception {
        n1.put(localKey, bytes("a"));
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("MGET", str(localKey)));
            assertThat(client.readResponse()).isEqualTo("*1");
            assertThat(client.readResponse()).isEqualTo("$1\r\na\r\n");
        }
    }

    @Test
    void msetLocal() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("MSET", str(localKey), "mv"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            assertThat(n1.get(localKey)).isEqualTo(bytes("mv"));
        }
    }

    @Test
    void getRemoteMoved() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(remoteKey)));
            assertThat(client.readResponse()).startsWith("-MOVED ");
        }
    }

    @Test
    void setRemoteMoved() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("SET", str(remoteKey), "v"));
            assertThat(client.readResponse()).startsWith("-MOVED ");
        }
    }

    @Test
    void movedContainsSlotAndAddress() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(remoteKey)));
            int slot = HashSlotRouter.slot(remoteKey);
            assertThat(client.readResponse())
                    .isEqualTo("-MOVED " + slot + " 127.0.0.1:7002");
        }
    }

    @Test
    void askWhenMigrating() throws Exception {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                RegionEpoch.INITIAL, "n1", "g1", true));
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).startsWith("-ASK ");
        }
    }

    @Test
    void askingAllowsMigratingRead() throws Exception {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                RegionEpoch.INITIAL, "n1", "g1", true));
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("ASKING"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            client.send(TestRespClient.command("SET",
                    str(localKey), "migrated"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            // ASKING 为 single-shot：读取前需再次置位
            client.send(TestRespClient.command("ASKING"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse())
                    .startsWith("$8")
                    .contains("migrated");
        }
    }

    @Test
    void askingIsOneShot() throws Exception {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                RegionEpoch.INITIAL, "n1", "g1", true));
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("ASKING"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).isEqualTo("$-1");
            // ASKING 仅对下一条命令有效：再次访问迁移 slot 应回到 ASK
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).startsWith("-ASK ");
        }
    }

    @Test
    void tryAgainWhenLeaderMissing() throws Exception {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), null, "g1", false));
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).startsWith("-TRYAGAIN");
        }
    }

    @Test
    void mgetMixedMoved() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("MGET", str(localKey), str(remoteKey)));
            assertThat(client.readResponse()).startsWith("-MOVED ");
        }
    }

    @Test
    void msetMixedMoved() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("MSET",
                    str(localKey), "a", str(remoteKey), "b"));
            assertThat(client.readResponse()).startsWith("-MOVED ");
        }
    }

    @Test
    void infoContainsClusterEnabled() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("INFO"));
            String response = client.readResponse();
            assertThat(response).contains("cluster_enabled:1");
        }
    }

    @Test
    void clusterSlotsListsRanges() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("CLUSTER", "SLOTS"));
            assertThat(client.readResponse()).isEqualTo("*2");
            assertThat(client.readResponse()).isEqualTo("*3");
            assertThat(client.readResponse()).isEqualTo(":0");
            assertThat(client.readResponse()).isEqualTo(":8191");
            assertThat(client.readResponse()).isEqualTo("*3");
        }
    }

    @Test
    void clusterNodesContainsNodes() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("CLUSTER", "NODES"));
            String response = client.readResponse();
            assertThat(response).contains("n1").contains("n2");
        }
    }

    @Test
    void unknownCommandError() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("NOSUCH"));
            assertThat(client.readResponse()).startsWith("-ERR unknown command");
        }
    }

    @Test
    void wrongArityError() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET"));
            assertThat(client.readResponse())
                    .startsWith("-ERR wrong number of arguments");
        }
    }

    @Test
    void pipelineCommands() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("SET", str(localKey), "p1")
                    + TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).isEqualTo("+OK");
            assertThat(client.readResponse()).isEqualTo("$2\r\np1\r\n");
        }
    }

    @Test
    void concurrentClients() throws Exception {
        int clients = 8;
        int ops = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clients);
        AtomicInteger failures = new AtomicInteger();
        for (int c = 0; c < clients; c++) {
            final int id = c;
            new Thread(() -> {
                try (TestRespClient client = client()) {
                    start.await();
                    byte[] key = keyIn("key:" + id + ":", 0, 8191, "a", "m");
                    for (int i = 0; i < ops; i++) {
                        client.send(TestRespClient.command(
                                "SET", str(key), "v" + id));
                        if (!client.readResponse().equals("+OK")) {
                            failures.incrementAndGet();
                        }
                        client.send(TestRespClient.command("GET", str(key)));
                        if (!client.readResponse().equals("$2\r\nv" + id + "\r\n")) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isZero();
    }

    @Test
    void leaderChangeUpdatesMoved() throws Exception {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), "n3", "g1", false));
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(localKey)));
            String response = client.readResponse();
            assertThat(response).startsWith("-MOVED ");
            assertThat(response).contains("7003");
        }
    }

    @Test
    void binarySafeValues() throws Exception {
        byte[] value = new byte[]{0, 1, 2, (byte) 0xff, (byte) 0xfe};
        n1.put(localKey, value);
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).isEqualTo(
                    "$5\r\n" + new String(value, StandardCharsets.UTF_8) + "\r\n");
        }
    }

    @Test
    void largeValueRoundTrip() throws Exception {
        byte[] value = new byte[64 * 1024];
        java.util.Arrays.fill(value, (byte) 'x');
        try (TestRespClient client = client()) {
            client.send("*3\r\n$3\r\nSET\r\n$" + str(localKey).length()
                    + "\r\n" + str(localKey) + "\r\n$65536\r\n"
                    + new String(value, StandardCharsets.ISO_8859_1) + "\r\n");
            assertThat(client.readResponse()).isEqualTo("+OK");
            client.send(TestRespClient.command("GET", str(localKey)));
            assertThat(client.readResponse()).startsWith("$65536");
        }
    }

    @Test
    void setExExpiry() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("SET", str(localKey), "v", "EX", "10"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            assertThat(n1.getEntry(localKey).expireTimestamp()).isGreaterThan(0);
        }
    }

    @Test
    void inlineCommandParsed() throws Exception {
        try (TestRespClient client = client()) {
            client.send("NOSUCH\r\n");
            assertThat(client.readResponse()).startsWith("-ERR unknown command");
        }
    }

    @Test
    void protocolErrorHandled() throws Exception {
        try (TestRespClient client = client()) {
            client.send("$abc\r\n");
            assertThat(client.readResponse()).startsWith("-ERR Protocol error");
        }
    }

    @Test
    void setOverwriteValue() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("SET", str(localKey), "one"));
            client.readResponse();
            client.send(TestRespClient.command("SET", str(localKey), "two"));
            assertThat(client.readResponse()).isEqualTo("+OK");
            assertThat(n1.get(localKey)).isEqualTo(bytes("two"));
        }
    }

    @Test
    void clusterUnknownSubcommandError() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("CLUSTER", "BOGUS"));
            assertThat(client.readResponse())
                    .startsWith("-ERR unknown CLUSTER subcommand");
        }
    }

    @Test
    void connectDisconnectCleanly() throws Exception {
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("PING"));
            assertThat(client.readResponse()).startsWith("-ERR unknown command");
        }
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("PING"));
            assertThat(client.readResponse()).startsWith("-ERR unknown command");
        }
    }

    @Test
    void gatewayMetricsTrackConnections() throws Exception {
        GatewayMetricsRegistry metrics = server.metrics();
        long before = metrics.snapshot().connections();
        try (TestRespClient client = client()) {
            client.send(TestRespClient.command("GET", str(localKey)));
            client.readResponse();
            assertThat(metrics.snapshot().connections()).isEqualTo(before + 1);
            assertThat(metrics.snapshot().qps()).isGreaterThan(0);
        }
    }

    @Test
    void keyslotConsistency() throws Exception {
        assertThat(table.routeSlot(HashSlotRouter.slot(localKey)).regionId())
                .isEqualTo(new RegionId(1));
        assertThat(table.routeSlot(HashSlotRouter.slot(remoteKey)).regionId())
                .isEqualTo(new RegionId(2));
    }

    private TestRespClient client() throws Exception {
        return new TestRespClient(server.boundPort());
    }

    private static RoutingTableEntry entry(RegionId id, String start, String end,
                                           int slotStart, int slotEnd,
                                           RegionEpoch epoch, String leader,
                                           String group, boolean migrating) {
        return new RoutingTableEntry(id, bytes(start), bytes(end),
                slotStart, slotEnd, epoch, leader, group, migrating);
    }

    private static byte[] keyIn(String prefix, int slotStart, int slotEnd,
                                String keyStart, String keyEnd) {
        for (int i = 0; i < 10_000; i++) {
            byte[] key = bytes(prefix + i);
            int slot = HashSlotRouter.slot(key);
            String text = str(key);
            if (slot >= slotStart && slot <= slotEnd
                    && text.compareTo(keyStart) >= 0
                    && text.compareTo(keyEnd) < 0) {
                return key;
            }
        }
        throw new IllegalStateException("no key in range");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
