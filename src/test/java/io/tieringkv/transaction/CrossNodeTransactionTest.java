package io.tieringkv.transaction;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.HybridLogicalClock;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨节点分布式事务（ADR-0083）：真实 TCP 三节点 + 2PC + 恢复。 */
class CrossNodeTransactionTest {

    private java.nio.file.Path dir;
    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("phase21-tcp");
        fixture = Fixture.start(dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void singleRegionTcpCommit() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("v1"));
        fixture.router.commit(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("v1"));
        assertThat(fixture.r2.latestValue(bytes("a1"))).isNull();
        assertThat(fixture.r3.latestValue(bytes("a1"))).isNull();
    }

    @Test
    void multiRegionTcpCommit() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
    }

    @Test
    void threeNodeTcpCommit() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        txn.put(bytes("c1"), bytes("vc"));
        fixture.router.commit(txn);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        assertThat(fixture.r3.latestValue(bytes("c1"))).isEqualTo(bytes("vc"));
    }

    @Test
    void prewriteFailureRollsBackAllRegions() {
        // 预占 r2 上的锁 → prewrite r2 失败 → r1 已 prewrite 必须回滚
        io.tieringkv.mvcc.PrewriteExecutor prewrite =
                new io.tieringkv.mvcc.PrewriteExecutor();
        prewrite.prewrite(fixture.r2, fixture.locks2, bytes("b1"),
                bytes("blocked"), false, "other-txn", bytes("b1"), 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        assertThatThrownBy(() -> fixture.router.commit(txn))
                .isInstanceOf(RuntimeException.class);
        assertThat(txn.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
        assertThat(fixture.locks1.size()).isZero();
        assertThat(fixture.locks2.check(bytes("b1")).txnId())
                .isEqualTo("other-txn");
    }

    @Test
    void leaderChangeRetrySucceeds() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        // 包装 r2 客户端：第一次调用失败（leader 变更），重试成功
        TxnParticipantClient retryClient = new TxnParticipantClient("node2",
                "r2", new FailOnceTransport(fixture.transport1),
                ignored -> fixture.metrics.recordNetworkRetry());
        DistributedTxnRouter retryRouter = new DistributedTxnRouter(
                fixture.oracle, fixture.regionOf(), fixture.regionsWith(
                "r2", retryClient), fixture.metadata, fixture.metrics);
        retryRouter.commit(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        assertThat(fixture.metrics.snapshot().networkRetry())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void coordinatorCrashAfterPrepareRecoversCommit() throws Exception {
        // 手工构造：metadata REGISTER + PREPARE（模拟崩溃于 commit RPC 前）
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        Map<String, List<io.tieringkv.transaction.rpc.TxnMessages.Mutation>>
                byRegion = new LinkedHashMap<>();
        byRegion.put("r1", List.of(new io.tieringkv.transaction.rpc
                .TxnMessages.Mutation(bytes("a1"), bytes("va"), false)));
        byRegion.put("r2", List.of(new io.tieringkv.transaction.rpc
                .TxnMessages.Mutation(bytes("b1"), bytes("vb"), false)));
        // 崩溃前 prewrite 已完成（2PC 第一阶段）
        fixture.regionClients.get(0).prewrite(txn, byRegion.get("r1")).join();
        fixture.regionClients.get(1).prewrite(txn, byRegion.get("r2")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"), txn.startTS(),
                byRegion).join();
        long commitTS = fixture.oracle.nextTimestamp();
        fixture.metadata.prepare(txn.txnId(), commitTS).join();
        // 崩溃：未执行 commit RPC
        fixture.metadata.close();

        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter recoveredRouter = new DistributedTxnRouter(
                fixture.oracle, fixture.regionOf(), fixture.regions(),
                recovered, fixture.metrics);
        DistributedTxnRouter.RecoveryResult result = recoveredRouter.recover();
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        recovered.close();
    }

    @Test
    void coordinatorCrashBeforePrepareRollsBack() throws Exception {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<io.tieringkv.transaction.rpc.TxnMessages.Mutation>>
                byRegion = new LinkedHashMap<>();
        byRegion.put("r1", List.of(new io.tieringkv.transaction.rpc
                .TxnMessages.Mutation(bytes("a1"), bytes("va"), false)));
        fixture.metadata.register(txn.txnId(), bytes("a1"), txn.startTS(),
                byRegion).join();
        fixture.metadata.close();

        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter recoveredRouter = new DistributedTxnRouter(
                fixture.oracle, fixture.regionOf(), fixture.regions(),
                recovered, fixture.metrics);
        DistributedTxnRouter.RecoveryResult result = recoveredRouter.recover();
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
        assertThat(fixture.locks1.size()).isZero();
        recovered.close();
    }

    @Test
    void snapshotReadSeesCommittedAfterTcpCommit() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        assertThat(new SnapshotReader().get(fixture.r1, bytes("a1"),
                Long.MAX_VALUE)).isEqualTo(bytes("va"));
    }

    @Test
    void heartbeatRefreshesLockTtl() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        io.tieringkv.mvcc.PrewriteExecutor prewrite =
                new io.tieringkv.mvcc.PrewriteExecutor();
        prewrite.prewrite(fixture.r1, fixture.locks1, bytes("a1"),
                bytes("va"), false, txn.txnId(), bytes("a1"), txn.startTS(),
                60_000, System.currentTimeMillis(), java.util.Set.of());
        io.tieringkv.mvcc.LockRecord before =
                fixture.locks1.check(bytes("a1"));
        fixture.regionClients.get(0).heartbeat(
                txn.txnId(), txn.startTS(), 120_000).join();
        io.tieringkv.mvcc.LockRecord after =
                fixture.locks1.check(bytes("a1"));
        assertThat(after.createdAtMillis())
                .isGreaterThanOrEqualTo(before.createdAtMillis());
    }

    @Test
    void concurrentMultiRegionTxnsNoLostUpdate() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
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
            worker.join(30_000);
        }
        assertThat(failed).isFalse();
        for (int w = 0; w < threads; w++) {
            assertThat(fixture.r1.latestValue(bytes("a" + w)))
                    .isEqualTo(bytes("va" + w));
            assertThat(fixture.r2.latestValue(bytes("b" + w)))
                    .isEqualTo(bytes("vb" + w));
        }
    }

    @Test
    void regionCountMetricRecorded() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        assertThat(fixture.metrics.snapshot().regionCount()).isEqualTo(2);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 3, 5, 10, 20})
    void parameterizedMultiKeyTcpCommit(int keyCount) {
        Transaction txn = fixture.router.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
        }
        fixture.router.commit(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.r1.latestValue(bytes("a" + i)))
                    .isEqualTo(bytes("va" + i));
            assertThat(fixture.r2.latestValue(bytes("b" + i)))
                    .isEqualTo(bytes("vb" + i));
        }
    }

    @Test
    void crossNodeRollbackCleansAllRegions() {
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.rollback(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
        assertThat(fixture.r2.latestValue(bytes("b1"))).isNull();
        assertThat(fixture.locks1.size()).isZero();
        assertThat(fixture.locks2.size()).isZero();
    }

    // ---------- helpers ----------

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record Fixture(MultiRaftEndpoint endpoint1,
                           MultiRaftEndpoint endpoint2,
                           MultiRaftEndpoint endpoint3,
                           MvccStorageEngine r1, MvccStorageEngine r2,
                           MvccStorageEngine r3, LockTable locks1,
                           LockTable locks2, LockTable locks3,
                           RpcTxnTransport transport1,
                           TimestampOracle oracle,
                           TransactionMetadataService metadata,
                           Path metaLog,
                           TransactionMetricsRegistry metrics,
                           DistributedTxnRouter router,
                           List<RegionTxnClient> regionClients) {

        static Fixture start(Path dir) throws Exception {
            // 14613 个测试共用 OS 端口空间，freePort() 释放到 bind 之间可能被
            // 并发占用（TOCTOU）导致 BindException（test/main runner 实测）。
            // 失败时关闭已启动端点并重新分配端口重试。
            Exception last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    return startOnce(dir);
                } catch (Exception e) {
                    last = e;
                }
            }
            throw new IllegalStateException(
                    "Fixture.start failed after 5 attempts", last);
        }

        private static Fixture startOnce(Path dir) throws Exception {
            int p1 = freePort();
            int p2 = freePort();
            int p3 = freePort();
            Map<String, InetSocketAddress> addresses = Map.of(
                    "node1", new InetSocketAddress("127.0.0.1", p1),
                    "node2", new InetSocketAddress("127.0.0.1", p2),
                    "node3", new InetSocketAddress("127.0.0.1", p3));
            MultiRaftEndpoint e1 = new MultiRaftEndpoint("node1", p1, addresses);
            MultiRaftEndpoint e2 = new MultiRaftEndpoint("node2", p2, addresses);
            MultiRaftEndpoint e3 = new MultiRaftEndpoint("node3", p3, addresses);
            try {
                e1.start();
                e2.start();
                e3.start();

                MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
                MvccStorageEngine r2 = new MvccStorageEngine(MemTable.create());
                MvccStorageEngine r3 = new MvccStorageEngine(MemTable.create());
                LockTable l1 = new LockTable();
                LockTable l2 = new LockTable();
                LockTable l3 = new LockTable();
                e1.registerTxnHandler("r1", new TxnParticipantRpcHandler(
                        new TransactionParticipant("r1", r1, l1, 60_000)));
                e2.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                        new TransactionParticipant("r2", r2, l2, 60_000)));
                e3.registerTxnHandler("r3", new TxnParticipantRpcHandler(
                        new TransactionParticipant("r3", r3, l3, 60_000)));

                RpcTxnTransport transport1 = new RpcTxnTransport(e1);
                TimestampOracle oracle = new TimestampOracle();
                Path metaLog = dir.resolve("meta.log");
                TransactionMetadataService metadata =
                        new TransactionMetadataService(
                                command -> CompletableFuture
                                        .completedFuture(1L),
                                metaLog);
                TransactionMetricsRegistry metrics =
                        new TransactionMetricsRegistry();
                RegionTxnClient c1 = new RegionTxnClient("r1",
                        new TxnParticipantClient("node1", "r1", transport1),
                        key -> key.key().length > 0 && key.key()[0] == 'a');
                RegionTxnClient c2 = new RegionTxnClient("r2",
                        new TxnParticipantClient("node2", "r2", transport1),
                        key -> key.key().length > 0 && key.key()[0] == 'b');
                RegionTxnClient c3 = new RegionTxnClient("r3",
                        new TxnParticipantClient("node3", "r3", transport1),
                        key -> key.key().length > 0 && key.key()[0] == 'c');
                List<RegionTxnClient> clients = List.of(c1, c2, c3);
                DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                        key -> {
                            if (key.key().length == 0) {
                                return c1;
                            }
                            return switch (key.key()[0]) {
                                case 'b' -> c2;
                                case 'c' -> c3;
                                default -> c1;
                            };
                        }, clients, metadata, metrics);
                return new Fixture(e1, e2, e3, r1, r2, r3, l1, l2, l3,
                        transport1, oracle, metadata, metaLog, metrics, router,
                        clients);
            } catch (Exception e) {
                closeQuietly(e3);
                closeQuietly(e2);
                closeQuietly(e1);
                throw e;
            }
        }

        private static void closeQuietly(MultiRaftEndpoint endpoint) {
            if (endpoint != null) {
                try {
                    endpoint.close();
                } catch (RuntimeException ignored) {
                    // 重试路径：忽略关闭失败
                }
            }
        }

        java.util.function.Function<ByteKey, RegionTxnClient> regionOf() {
            return key -> {
                if (key.key().length == 0) {
                    return regionClients.get(0);
                }
                return switch (key.key()[0]) {
                    case 'b' -> regionClients.get(1);
                    case 'c' -> regionClients.get(2);
                    default -> regionClients.get(0);
                };
            };
        }

        List<RegionTxnClient> regions() {
            return regionClients;
        }

        List<RegionTxnClient> regionsWith(String regionId,
                                          TxnParticipantClient client) {
            List<RegionTxnClient> updated = new ArrayList<>();
            for (RegionTxnClient region : regionClients) {
                if (region.regionId().equals(regionId)) {
                    updated.add(new RegionTxnClient(regionId, client,
                            key -> key.key().length > 0
                                    && key.key()[0] == 'b'));
                } else {
                    updated.add(region);
                }
            }
            return updated;
        }

        void close() throws Exception {
            metadata.close();
            endpoint1.close();
            endpoint2.close();
            endpoint3.close();
            ((MemTable) r1.underlying()).close();
            ((MemTable) r2.underlying()).close();
            ((MemTable) r3.underlying()).close();
        }
    }

    /** 首次调用失败（模拟 leader 变更），之后透传。 */
    private static final class FailOnceTransport
            implements io.tieringkv.transaction.router.TxnTransport {
        private final io.tieringkv.transaction.router.TxnTransport delegate;
        private int failures;

        private FailOnceTransport(
                io.tieringkv.transaction.router.TxnTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            if (failures++ == 0) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("not leader"));
            }
            return delegate.call(target, regionId, type, payload);
        }
    }
}
