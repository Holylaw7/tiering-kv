package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.RpcTxnTransport;
import io.tieringkv.transaction.router.TxnParticipantClient;
import io.tieringkv.transaction.rpc.TxnMessages;
import io.tieringkv.transaction.rpc.TxnParticipantRpcHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** CI 容器运行时 E2E（TD-048）：TCP 全链路 + 故障路径（JVM 内等价）。 */
class CiTransactionE2ETest {

    private java.nio.file.Path dir;
    private E2E fixture;

    @BeforeEach
    void setUp() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("phase24-e2e");
        fixture = E2E.start(dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void setGetRoundTrip() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        TxnMessages.Response response = fixture.regionA.get(bytes("a1"))
                .join();
        assertThat(response.message()).isEqualTo("va");
    }

    @Test
    void crossRegionTxn() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        assertThat(fixture.engineB.latestValue(bytes("b1")))
                .isEqualTo(bytes("vb"));
    }

    @Test
    void msetEquivalent() {
        Transaction txn = fixture.router.begin();
        for (int i = 0; i < 5; i++) {
            txn.put(bytes("a" + i), bytes("v" + i));
        }
        fixture.router.commit(txn);
        for (int i = 0; i < 5; i++) {
            assertThat(fixture.engineA.latestValue(bytes("a" + i)))
                    .isEqualTo(bytes("v" + i));
        }
    }

    @Test
    void killCoordinatorRecovers() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        DistributedTxnRouter restarted = fixture.restartCoordinator();
        Transaction after = restarted.begin();
        after.put(bytes("a2"), bytes("va2"));
        try {
            restarted.commit(after);
        } catch (RuntimeException ignored) {
            // 瞬时故障
        }
        restarted.recover();
        assertThat(fixture.engineA.latestValue(bytes("a2")))
                .isEqualTo(bytes("va2"));
    }

    @Test
    void killParticipantRecovers() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        fixture.restartParticipantB();
        Transaction after = fixture.router.begin();
        after.put(bytes("b2"), bytes("vb2"));
        fixture.router.commit(after);
        assertThat(fixture.engineB.latestValue(bytes("b2")))
                .isEqualTo(bytes("vb2"));
    }

    @Test
    void networkPartitionNoLostCommit() {
        for (int i = 0; i < 20; i++) {
            Transaction txn = fixture.router.begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            try {
                fixture.router.commit(txn);
            } catch (RuntimeException ignored) {
                // 丢包/瞬时故障
            }
        }
        fixture.router.recover();
        assertThat(fixture.engineA.latestValue(bytes("a19")))
                .isEqualTo(bytes("va19"));
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50})
    void parameterizedE2E(int txnCount) {
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router.begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
            fixture.router.commit(txn);
        }
        assertThat(fixture.engineA.latestValue(bytes("a" + (txnCount - 1))))
                .isEqualTo(bytes("va" + (txnCount - 1)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record E2E(MultiRaftEndpoint coord, MultiRaftEndpoint pa,
                       MultiRaftEndpoint pb, MultiRaftEndpoint meta,
                       MvccStorageEngine engineA, MvccStorageEngine engineB,
                       DistributedTxnRouter router, RegionTxnClient regionA,
                       TimestampOracle oracle,
                       TransactionMetadataService metadata) implements
            AutoCloseable {

        static E2E start(Path dir) throws Exception {
            Map<String, InetSocketAddress> addresses = Map.of(
                    "pa", new InetSocketAddress("127.0.0.1", freePort()),
                    "pb", new InetSocketAddress("127.0.0.1", freePort()),
                    "coord", new InetSocketAddress("127.0.0.1", freePort()),
                    "meta", new InetSocketAddress("127.0.0.1", freePort()));
            MultiRaftEndpoint coord = new MultiRaftEndpoint("coord",
                    addresses.get("coord").getPort(), addresses);
            MultiRaftEndpoint pa = new MultiRaftEndpoint("pa",
                    addresses.get("pa").getPort(), addresses);
            MultiRaftEndpoint pb = new MultiRaftEndpoint("pb",
                    addresses.get("pb").getPort(), addresses);
            MultiRaftEndpoint meta = new MultiRaftEndpoint("meta",
                    addresses.get("meta").getPort(), addresses);
            coord.start();
            pa.start();
            pb.start();
            meta.start();
            MvccStorageEngine engineA = new MvccStorageEngine(MemTable.create());
            MvccStorageEngine engineB = new MvccStorageEngine(MemTable.create());
            pa.registerTxnHandler("r1", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r1", engineA,
                            new LockTable(), 60_000)));
            pb.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r2", engineB,
                            new LockTable(), 60_000)));
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            RpcTxnTransport transport = new RpcTxnTransport(coord);
            RegionTxnClient regionA = new RegionTxnClient("r1",
                    new TxnParticipantClient("pa", "r1", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'a');
            RegionTxnClient regionB = new RegionTxnClient("r2",
                    new TxnParticipantClient("pb", "r2", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'b');
            TimestampOracle oracle = new TimestampOracle();
            DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                    key -> key.key().length > 0 && key.key()[0] == 'b'
                            ? regionB : regionA,
                    List.of(regionA, regionB), metadata,
                    new TransactionMetricsRegistry());
            return new E2E(coord, pa, pb, meta, engineA, engineB, router,
                    regionA, oracle, metadata);
        }

        DistributedTxnRouter restartCoordinator() {
            RpcTxnTransport transport = new RpcTxnTransport(coord);
            RegionTxnClient regionA = new RegionTxnClient("r1",
                    new TxnParticipantClient("pa", "r1", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'a');
            RegionTxnClient regionB = new RegionTxnClient("r2",
                    new TxnParticipantClient("pb", "r2", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'b');
            return new DistributedTxnRouter(oracle,
                    key -> key.key().length > 0 && key.key()[0] == 'b'
                            ? regionB : regionA,
                    List.of(regionA, regionB), metadata,
                    new TransactionMetricsRegistry());
        }

        void restartParticipantB() throws Exception {
            int port = pb.boundPort();
            pb.close();
            Thread.sleep(1_000);
            MultiRaftEndpoint restarted = new MultiRaftEndpoint("pb",
                    port, Map.of("pb",
                    new InetSocketAddress("127.0.0.1", port)));
            restarted.start();
            restarted.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r2", engineB,
                            new LockTable(), 60_000)));
        }

        @Override
        public void close() throws Exception {
            metadata.close();
            coord.close();
            pa.close();
            pb.close();
            meta.close();
            ((MemTable) engineA.underlying()).close();
            ((MemTable) engineB.underlying()).close();
        }
    }
}
