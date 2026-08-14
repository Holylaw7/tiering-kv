package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SnapshotReader;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 容器运行时事务链路（ADR-0093）：Gateway→Coordinator→Participant→Metadata 全 TCP。 */
class ContainerTransactionRuntimeTest {

    @TempDir
    Path dir;

    private RuntimeFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = RuntimeFixture.start(dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void txnRpcFullChain() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        assertThat(fixture.engineA.latestValue(bytes("a1")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.engineB.latestValue(bytes("b1")))
                .isEqualTo(bytes("vb"));
    }

    @Test
    void txnGetOverRpc() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        TxnMessages.Response response = fixture.regionA.get(bytes("a1"))
                .join();
        assertThat(response.message()).isEqualTo("va");
    }

    @Test
    void checkStatusOverRpc() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.regionA.prewrite(txn, List.of(new TxnMessages.Mutation(
                bytes("a1"), bytes("va"), false))).join();
        TxnMessages.Response response = fixture.regionA.checkStatus(
                txn.txnId(), txn.startTS()).join();
        assertThat(response.message())
                .isEqualTo(TxnMessages.ParticipantState.LOCKED.name());
    }

    @Test
    void resolveLockOverRpc() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.regionA.prewrite(txn, List.of(new TxnMessages.Mutation(
                bytes("a1"), bytes("va"), false))).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), Map.of("r1", List.of(
                        new TxnMessages.Mutation(bytes("a1"),
                                bytes("va"), false)))).join();
        fixture.metadata.prepare(txn.txnId(), 9).join();
        TxnMessages.Response response = fixture.regionA.resolveLock(
                txn.txnId(), txn.startTS(), 9, bytes("a1"),
                List.of(new TxnMessages.Mutation(bytes("a1"),
                        bytes("va"), false)), false).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(fixture.engineA.latestValue(bytes("a1")))
                .isEqualTo(bytes("va"));
    }

    @Test
    void remoteMetadataDecisions() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        assertThat(fixture.metadata.state().get(txn.txnId()).state())
                .isNotNull();
    }

    @Test
    void participantRestartRecovers() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        fixture.restartParticipantB();
        assertThat(fixture.engineB.latestValue(bytes("b1")))
                .isEqualTo(bytes("vb"));
        Transaction after = fixture.router.begin();
        after.put(bytes("b2"), bytes("vb2"));
        fixture.router.commit(after);
        assertThat(fixture.engineB.latestValue(bytes("b2")))
                .isEqualTo(bytes("vb2"));
    }

    @Test
    void coordinatorRestartRecovers() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        DistributedTxnRouter restarted = fixture.restartCoordinator();
        Transaction after = restarted.begin();
        after.put(bytes("a2"), bytes("va2"));
        try {
            restarted.commit(after);
        } catch (RuntimeException transientFailure) {
            // 瞬时故障：恢复兜底
        }
        restarted.recover();
        assertThat(fixture.engineA.latestValue(bytes("a2")))
                .isEqualTo(bytes("va2"));
    }

    @Test
    void metadataRestartRecoversDecisions() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        TransactionMetadataService recovered = fixture.restartMetadata();
        assertThat(recovered.state().get(txn.txnId()).state()).isNotNull();
        recovered.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 3, 5, 6, 10,
            12, 20, 24, 40, 48, 80})
    void parameterizedMultiKeyTcp(int keyCount) {
        Transaction txn = fixture.router.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
        }
        fixture.router.commit(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engineA.latestValue(bytes("a" + i)))
                    .isEqualTo(bytes("va" + i));
            assertThat(fixture.engineB.latestValue(bytes("b" + i)))
                    .isEqualTo(bytes("vb" + i));
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 2, 4, 8, 16, 32, 3, 5, 6, 10, 12, 20})
    void parameterizedRestartRounds(int rounds) throws Exception {
        DistributedTxnRouter current = fixture.router;
        for (int i = 0; i < rounds; i++) {
            Transaction txn = current.begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            current.commit(txn);
            current = fixture.restartCoordinator();
        }
        assertThat(fixture.engineA.latestValue(bytes("a" + (rounds - 1))))
                .isEqualTo(bytes("va" + (rounds - 1)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record RuntimeFixture(MultiRaftEndpoint metadataEndpoint,
                                  MultiRaftEndpoint participantAEndpoint,
                                  MultiRaftEndpoint participantBEndpoint,
                                  MultiRaftEndpoint coordinatorEndpoint,
                                  MvccStorageEngine engineA,
                                  MvccStorageEngine engineB,
                                  TimestampOracle oracle,
                                  TransactionMetadataService metadata,
                                  DistributedTxnRouter router,
                                  RegionTxnClient regionA,
                                  RegionTxnClient regionB,
                                  Path dir) implements AutoCloseable {

        static RuntimeFixture start(Path dir) throws Exception {
            Map<String, InetSocketAddress> addresses = Map.of(
                    "meta", new InetSocketAddress("127.0.0.1", freePort()),
                    "pa", new InetSocketAddress("127.0.0.1", freePort()),
                    "pb", new InetSocketAddress("127.0.0.1", freePort()),
                    "coord", new InetSocketAddress("127.0.0.1", freePort()));
            MultiRaftEndpoint meta = new MultiRaftEndpoint("meta",
                    addresses.get("meta").getPort(), addresses);
            MultiRaftEndpoint pa = new MultiRaftEndpoint("pa",
                    addresses.get("pa").getPort(), addresses);
            MultiRaftEndpoint pb = new MultiRaftEndpoint("pb",
                    addresses.get("pb").getPort(), addresses);
            MultiRaftEndpoint coord = new MultiRaftEndpoint("coord",
                    addresses.get("coord").getPort(), addresses);
            meta.start();
            pa.start();
            pb.start();
            coord.start();
            MvccStorageEngine engineA = new MvccStorageEngine(MemTable.create());
            MvccStorageEngine engineB = new MvccStorageEngine(MemTable.create());
            LockTable locksA = new LockTable();
            LockTable locksB = new LockTable();
            pa.registerTxnHandler("r1", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r1", engineA, locksA, 60_000)));
            pb.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r2", engineB, locksB, 60_000)));
            Path metaLog = dir.resolve("meta.log");
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            payload -> meta.callTxn("meta", "meta",
                                    RpcMessageType.TXN_METADATA, payload)
                                    .thenApply(frame -> 1L),
                            metaLog);
            meta.registerTxnHandler("meta", (frame, groupId, payload) -> {
                if (frame.type() == RpcMessageType.TXN_METADATA) {
                    metadata.applyLocal(
                            io.tieringkv.transaction.metadata.TxnMetaCodec
                                    .decode(payload));
                    return new io.tieringkv.cluster.rpc.RpcFrame(
                            frame.requestId(),
                            RpcMessageType.TXN_METADATA_RESPONSE,
                            io.tieringkv.transaction.rpc.TxnRpcCodec
                                    .encodeResponse(TxnMessages.Response.ok()));
                }
                throw new IllegalArgumentException("unexpected");
            });
            RpcTxnTransport transport = new RpcTxnTransport(coord);
            TimestampOracle oracle = new TimestampOracle();
            RegionTxnClient regionA = new RegionTxnClient("r1",
                    new TxnParticipantClient("pa", "r1", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'a');
            RegionTxnClient regionB = new RegionTxnClient("r2",
                    new TxnParticipantClient("pb", "r2", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'b');
            List<RegionTxnClient> regions = List.of(regionA, regionB);
            DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                    key -> key.key().length > 0 && key.key()[0] == 'b'
                            ? regionB : regionA,
                    regions, metadata, new TransactionMetricsRegistry());
            return new RuntimeFixture(meta, pa, pb, coord, engineA, engineB,
                    oracle, metadata, router, regionA, regionB, dir);
        }

        void restartParticipantB() throws Exception {
            int port = participantBEndpoint.boundPort();
            participantBEndpoint.close();
            // 等待旧监听释放端口（Linux CI 上 1s 不够，轮询探测）
            for (int i = 0; i < 50; i++) {
                try (java.net.ServerSocket probe = new java.net.ServerSocket(
                        port, 50, java.net.InetAddress.getLoopbackAddress())) {
                    break;
                } catch (java.io.IOException e) {
                    Thread.sleep(100);
                }
            }
            MultiRaftEndpoint restarted = new MultiRaftEndpoint("pb",
                    port, Map.of("pb",
                    new InetSocketAddress("127.0.0.1", port)));
            restarted.start();
            restarted.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r2", engineB,
                            new LockTable(), 60_000)));
        }

        DistributedTxnRouter restartCoordinator() throws Exception {
            // 协调器重启：复用端点（无状态），重建 Router + 客户端
            RpcTxnTransport transport = new RpcTxnTransport(
                    coordinatorEndpoint);
            RegionTxnClient regionA = new RegionTxnClient("r1",
                    new TxnParticipantClient("pa", "r1", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'a');
            RegionTxnClient regionB = new RegionTxnClient("r2",
                    new TxnParticipantClient("pb", "r2", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'b');
            List<RegionTxnClient> regions = List.of(regionA, regionB);
            return new DistributedTxnRouter(oracle,
                    key -> key.key().length > 0 && key.key()[0] == 'b'
                            ? regionB : regionA,
                    regions, metadata, new TransactionMetricsRegistry());
        }

        TransactionMetadataService restartMetadata() throws Exception {
            return TransactionMetadataService.recover(dir.resolve("meta.log"),
                    payload -> CompletableFuture.completedFuture(1L));
        }

        @Override
        public void close() throws Exception {
            metadata.close();
            metadataEndpoint.close();
            participantAEndpoint.close();
            participantBEndpoint.close();
            coordinatorEndpoint.close();
            ((MemTable) engineA.underlying()).close();
            ((MemTable) engineB.underlying()).close();
        }
    }
}
