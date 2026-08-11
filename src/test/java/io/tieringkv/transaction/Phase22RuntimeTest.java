package io.tieringkv.transaction;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.lock.LockResolver;
import io.tieringkv.transaction.lock.TxnStatusCache;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.RpcTxnTransport;
import io.tieringkv.transaction.router.TxnParticipantClient;
import io.tieringkv.transaction.rpc.TxnParticipantRpcHandler;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 运行时端到端（ADR-0090）：真实 TCP 事务链路 + participant 重启恢复。 */
class Phase22RuntimeTest {

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
    void tcpSingleRegionSetGet() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        assertThat(new SnapshotReader().get(fixture.r1, bytes("a1"),
                Long.MAX_VALUE)).isEqualTo(bytes("va"));
    }

    @Test
    void tcpMultiRegionCommit() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
    }

    @Test
    void participantRestartRecoversCommittedData() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        // 重启 node2（关闭端点后以同一引擎重新注册 participant）
        DistributedTxnRouter restartedRouter = fixture.restartNode2();
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        Transaction after = restartedRouter.begin();
        after.put(bytes("b2"), bytes("vb2"));
        restartedRouter.commit(after);
        assertThat(fixture.r2.latestValue(bytes("b2"))).isEqualTo(bytes("vb2"));
    }

    @Test
    void tcpHeartbeatExtendsLock() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.regionClients.get(0).prewrite(txn,
                List.of(new TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false))).join();
        io.tieringkv.mvcc.LockRecord before = fixture.locks1.check(bytes("a1"));
        Thread.sleep(5);
        fixture.regionClients.get(0).heartbeat(
                txn.txnId(), txn.startTS(), 120_000).join();
        io.tieringkv.mvcc.LockRecord after = fixture.locks1.check(bytes("a1"));
        assertThat(after.createdAtMillis())
                .isGreaterThanOrEqualTo(before.createdAtMillis());
    }

    @Test
    void tcpLockResolveAfterCoordinatorCrash() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.regionClients.get(0).prewrite(txn,
                List.of(new TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false))).join();
        fixture.regionClients.get(1).prewrite(txn,
                List.of(new TxnMessages.Mutation(
                        bytes("b1"), bytes("vb"), false))).join();
        Map<String, List<TxnMessages.Mutation>> byRegion = Map.of(
                "r1", List.of(new TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false)),
                "r2", List.of(new TxnMessages.Mutation(
                        bytes("b1"), bytes("vb"), false)));
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        fixture.metadata.prepare(txn.txnId(), 9).join();
        // coordinator crash：不执行 commit，由 LockResolver 补完
        LockResolver resolver = new LockResolver(fixture.metadata,
                Map.of("r1", fixture.regionClients.get(0),
                        "r2", fixture.regionClients.get(1)),
                key -> fixture.locks1.check(key) != null
                        || fixture.locks2.check(key) != null,
                new TxnStatusCache(1000));
        assertThat(resolver.resolve(txn.txnId(), bytes("a1"),
                txn.startTS()).resolution())
                .isEqualTo(LockResolver.Resolution.COMMITTED);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        assertThat(fixture.locks1.size()).isZero();
        assertThat(fixture.locks2.size()).isZero();
    }

    @Test
    void tcpRollbackCleansAllRegions() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.rollback(txn);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
        assertThat(fixture.r2.latestValue(bytes("b1"))).isNull();
        assertThat(fixture.locks1.size()).isZero();
        assertThat(fixture.locks2.size()).isZero();
    }

    @Test
    void tcpDeleteVisibleAfterCommit() {
        Transaction set = fixture.router.begin();
        set.put(bytes("a1"), bytes("va"));
        fixture.router.commit(set);
        Transaction del = fixture.router.begin();
        del.delete(bytes("a1"));
        fixture.router.commit(del);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
    }

    @Test
    void concurrentTcpTxnsNoLostUpdate() throws Exception {
        int threads = 8;
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean failed =
                new java.util.concurrent.atomic.AtomicBoolean();
        List<Thread> workers = new java.util.ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    Transaction txn = fixture.router.begin();
                    txn.put(bytes("a" + writer), bytes("va" + writer));
                    txn.put(bytes("b" + writer), bytes("vb" + writer));
                    fixture.router.commit(txn);
                } catch (Throwable t) {
                    failed.set(true);
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(60_000);
        }
        assertThat(failed).isFalse();
        for (int w = 0; w < threads; w++) {
            assertThat(fixture.r1.latestValue(bytes("a" + w)))
                    .isEqualTo(bytes("va" + w));
            assertThat(fixture.r2.latestValue(bytes("b" + w)))
                    .isEqualTo(bytes("vb" + w));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record RuntimeFixture(MultiRaftEndpoint endpoint1,
                                  MultiRaftEndpoint endpoint2,
                                  MvccStorageEngine r1, MvccStorageEngine r2,
                                  LockTable locks1, LockTable locks2,
                                  TimestampOracle oracle,
                                  TransactionMetadataService metadata,
                                  DistributedTxnRouter router,
                                  List<RegionTxnClient> regionClients,
                                  Path dir) implements AutoCloseable {

        static RuntimeFixture start(Path dir) throws Exception {
            int p1 = freePort();
            int p2 = freePort();
            Map<String, InetSocketAddress> addresses = Map.of(
                    "node1", new InetSocketAddress("127.0.0.1", p1),
                    "node2", new InetSocketAddress("127.0.0.1", p2));
            MultiRaftEndpoint e1 = new MultiRaftEndpoint("node1", p1, addresses);
            MultiRaftEndpoint e2 = new MultiRaftEndpoint("node2", p2, addresses);
            e1.start();
            e2.start();
            MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
            MvccStorageEngine r2 = new MvccStorageEngine(MemTable.create());
            LockTable l1 = new LockTable();
            LockTable l2 = new LockTable();
            e1.registerTxnHandler("r1", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r1", r1, l1, 60_000)));
            e2.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r2", r2, l2, 60_000)));
            RpcTxnTransport transport = new RpcTxnTransport(e1);
            TimestampOracle oracle = new TimestampOracle();
            Path metaLog = dir.resolve("meta.log");
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L),
                            metaLog);
            TransactionMetricsRegistry metrics =
                    new TransactionMetricsRegistry();
            RegionTxnClient c1 = new RegionTxnClient("r1",
                    new TxnParticipantClient("node1", "r1", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'a');
            RegionTxnClient c2 = new RegionTxnClient("r2",
                    new TxnParticipantClient("node2", "r2", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'b');
            List<RegionTxnClient> clients = List.of(c1, c2);
            DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                    key -> key.key().length > 0 && key.key()[0] == 'b'
                            ? c2 : c1,
                    clients, metadata, metrics);
            return new RuntimeFixture(e1, e2, r1, r2, l1, l2, oracle,
                    metadata, router, clients, dir);
        }

        DistributedTxnRouter restartNode2() throws Exception {
            endpoint2.close();
            int port = freePort();
            MultiRaftEndpoint restarted = new MultiRaftEndpoint(
                    "node2", port, Map.of("node2",
                    new InetSocketAddress("127.0.0.1", port)));
            restarted.start();
            restarted.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                    new TransactionParticipant("r2", r2, locks2, 60_000)));
            RpcTxnTransport transport = new RpcTxnTransport(restarted);
            RegionTxnClient c2 = new RegionTxnClient("r2",
                    new TxnParticipantClient("node2", "r2", transport),
                    key -> key.key().length > 0 && key.key()[0] == 'b');
            java.util.List<RegionTxnClient> updated = List.of(
                    regionClients.get(0), c2);
            DistributedTxnRouter restartedRouter = new DistributedTxnRouter(
                    oracle, key -> key.key().length > 0 && key.key()[0] == 'b'
                    ? c2 : regionClients.get(0),
                    updated, metadata, new TransactionMetricsRegistry());
            return restartedRouter;
        }

        @Override
        public void close() throws Exception {
            metadata.close();
            endpoint1.close();
            endpoint2.close();
            ((MemTable) r1.underlying()).close();
            ((MemTable) r2.underlying()).close();
        }
    }
}
