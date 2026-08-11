package io.tieringkv.transaction;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.LocalTxnTransport;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.TxnParticipantClient;
import io.tieringkv.transaction.router.TxnTransport;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分布式事务混沌（ADR-0086）：分区/超时/丢包/崩溃/恢复，无幻影/无丢失/无永久锁。 */
class Phase21DistributedChaosTest {

    @TempDir
    Path dir;

    @Test
    void partitionDuringPrewriteNoPhantomCommit() throws Exception {
        Chaos chaos = chaos(new FailAlways("r2"));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        assertThatThrownBy(() -> chaos.router.commit(txn))
                .isInstanceOf(RuntimeException.class);
        assertThat(chaos.r1.latestValue(bytes("a1"))).isNull();
        assertThat(chaos.r2.latestValue(bytes("b1"))).isNull();
        assertThat(chaos.locks1.size()).isZero();
        chaos.close();
    }

    @Test
    void partitionDuringCommitDecisionDurable() throws Exception {
        Chaos chaos = chaos(new FailCommitTimes("r2", 4));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        assertThatThrownBy(() -> chaos.router.commit(txn))
                .isInstanceOf(RuntimeException.class);
        assertThat(txn.state()).isEqualTo(Transaction.State.PREPARED);
        DistributedTxnRouter.RecoveryResult result = chaos.router.recover();
        assertThat(result.committed()).isEqualTo(1);
        assertThat(chaos.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(chaos.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        chaos.close();
    }

    @Test
    void killParticipantDuringPrewriteRetrySucceeds() throws Exception {
        Chaos chaos = chaos(new FailNTimes("r2", 2));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        chaos.router.commit(txn);
        assertThat(chaos.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        chaos.close();
    }

    @Test
    void killParticipantDuringCommitRetrySucceeds() throws Exception {
        Chaos chaos = chaos(new FailCommitTimes("r2", 1));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        chaos.router.commit(txn);
        assertThat(chaos.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        chaos.close();
    }

    @Test
    void networkLoss5PercentNoLostCommit() throws Exception {
        Chaos chaos = chaos(new LossyTransport("r2", 5));
        for (int i = 0; i < 50; i++) {
            Transaction txn = chaos.router.begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
            chaos.router.commit(txn);
        }
        assertThat(chaos.r2.latestValue(bytes("b49"))).isEqualTo(bytes("vb49"));
        chaos.close();
    }

    @Test
    void networkLoss15PercentNoLostCommit() throws Exception {
        Chaos chaos = chaos(new LossyTransport("r2", 15));
        for (int i = 0; i < 20; i++) {
            Transaction txn = chaos.router.begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    chaos.router.commit(txn);
                    break;
                } catch (RuntimeException transientFailure) {
                    // 丢包导致提案失败：客户端重试（幂等）
                }
            }
        }
        chaos.router.recover();
        assertThat(chaos.r2.latestValue(bytes("b19"))).isEqualTo(bytes("vb19"));
        chaos.close();
    }

    @Test
    void delayedTransportNoLostCommit() throws Exception {
        Chaos chaos = chaos(new DelayedTransport("r2", 20));
        for (int i = 0; i < 20; i++) {
            Transaction txn = chaos.router.begin();
            txn.put(bytes("b" + i), bytes("vb" + i));
            chaos.router.commit(txn);
        }
        assertThat(chaos.r2.latestValue(bytes("b19"))).isEqualTo(bytes("vb19"));
        chaos.close();
    }

    @Test
    void coordinatorRestartRecoversAll() throws Exception {
        Chaos chaos = chaos(new FailAlways("r2"));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<TxnMessages.Mutation>> byRegion = Map.of("r1",
                List.of(new TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false)));
        chaos.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        chaos.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        chaos.close(); // 协调器崩溃
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(chaos.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter recoveredRouter = new DistributedTxnRouter(
                chaos.oracle, chaos.regionOf(), chaos.regionClients(),
                recovered, chaos.metrics);
        // 故障恢复后重放：REGISTERED → 回滚（无幻影）
        assertThat(recoveredRouter.recover().rolledBack()).isEqualTo(1);
        assertThat(chaos.r1.latestValue(bytes("a1"))).isNull();
        assertThat(chaos.locks1.size()).isZero();
        recovered.close();
    }

    @Test
    void noPermanentLockAfterFailedPrewrite() throws Exception {
        Chaos chaos = chaos(new FailAlways("r2"));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        try {
            chaos.router.commit(txn);
        } catch (RuntimeException ignored) {
            // 预期失败
        }
        assertThat(chaos.locks1.size()).isZero();
        chaos.close();
    }

    @Test
    void noPermanentLockAfterFailedCommit() throws Exception {
        Chaos chaos = chaos(new FailCommitTimes("r2", 4));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        try {
            chaos.router.commit(txn);
        } catch (RuntimeException ignored) {
            // 预期失败
        }
        chaos.router.recover();
        assertThat(chaos.locks1.size()).isZero();
        assertThat(chaos.locks2.size()).isZero();
        chaos.close();
    }

    @Test
    void concurrentChaosWritersNoPermanentLock() throws Exception {
        Chaos chaos = chaos(new LossyTransport("r2", 20));
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        Transaction txn = chaos.router.begin();
                        txn.put(bytes("a" + writer + "-" + i),
                                bytes("va"));
                        txn.put(bytes("b" + writer + "-" + i),
                                bytes("vb"));
                        try {
                            chaos.router.commit(txn);
                        } catch (RuntimeException conflict) {
                            chaos.router.rollback(txn);
                        }
                    }
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
        chaos.router.recover();
        assertThat(failed).isFalse();
        assertThat(chaos.locks1.size()).isZero();
        assertThat(chaos.locks2.size()).isZero();
        chaos.close();
    }

    @Test
    void chaosRecoveryIdempotent() throws Exception {
        Chaos chaos = chaos(new FailCommitTimes("r2", 4));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        try {
            chaos.router.commit(txn);
        } catch (RuntimeException ignored) {
            // 预期失败
        }
        assertThat(chaos.router.recover().committed()).isEqualTo(1);
        assertThat(chaos.router.recover().committed()).isZero();
        chaos.close();
    }

    @ParameterizedTest(name = "failureAt {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void failureAtPositionEventuallyCommits(int failurePosition)
            throws Exception {
        Chaos chaos = chaos(new FailAtPosition("r2", failurePosition));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        try {
            chaos.router.commit(txn);
        } catch (RuntimeException ignored) {
            // 注入点故障：可能由客户端重试或恢复补完
        }
        chaos.router.recover();
        assertThat(chaos.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(chaos.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        assertThat(chaos.locks1.size()).isZero();
        assertThat(chaos.locks2.size()).isZero();
        chaos.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void multiKeyChaosNoPartialCommit(int keyCount) throws Exception {
        Chaos chaos = chaos(new FailCommitTimes("r2", 4));
        Transaction txn = chaos.router.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
        }
        try {
            chaos.router.commit(txn);
        } catch (RuntimeException ignored) {
            // 预期失败
        }
        DistributedTxnRouter.RecoveryResult result = chaos.router.recover();
        assertThat(result.committed()).isEqualTo(1);
        for (int i = 0; i < keyCount; i++) {
            assertThat(chaos.r1.latestValue(bytes("a" + i)))
                    .isEqualTo(bytes("va" + i));
            assertThat(chaos.r2.latestValue(bytes("b" + i)))
                    .isEqualTo(bytes("vb" + i));
        }
        chaos.close();
    }

    @ParameterizedTest(name = "loss {0}")
    @ValueSource(ints = {10, 20, 30})
    void parameterizedLossNoLostCommit(int loss) throws Exception {
        Chaos chaos = chaos(new LossyTransport("r2", loss));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        chaos.router.commit(txn);
        assertThat(chaos.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        chaos.close();
    }

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(ints = {5, 10, 20, 50})
    void parameterizedDelayNoLostCommit(int delayMillis) throws Exception {
        Chaos chaos = chaos(new DelayedTransport("r2", delayMillis));
        Transaction txn = chaos.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        chaos.router.commit(txn);
        assertThat(chaos.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        chaos.close();
    }

    // ---------- harness ----------

    private Chaos chaos(Fault fault) throws Exception {
        MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
        MvccStorageEngine r2 = new MvccStorageEngine(MemTable.create());
        LockTable l1 = new LockTable();
        LockTable l2 = new LockTable();
        LocalTxnTransport t1 = new LocalTxnTransport(
                new TransactionParticipant("r1", r1, l1, 60_000));
        LocalTxnTransport t2 = new LocalTxnTransport(
                new TransactionParticipant("r2", r2, l2, 60_000));
        TimestampOracle oracle = new TimestampOracle();
        Path metaLog = dir.resolve("chaos-" + System.nanoTime() + ".log");
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        metaLog);
        TransactionMetricsRegistry metrics =
                new TransactionMetricsRegistry();
        TxnTransport r2Transport = fault == null ? t2 : fault.wrap(t2);
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", t1),
                key -> key.key().length > 0 && key.key()[0] == 'a');
        RegionTxnClient c2 = new RegionTxnClient("r2",
                new TxnParticipantClient("n2", "r2", r2Transport),
                key -> key.key().length > 0 && key.key()[0] == 'b');
        List<RegionTxnClient> clients = List.of(c1, c2);
        DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                key -> key.key().length > 0 && key.key()[0] == 'b' ? c2 : c1,
                clients, metadata, metrics);
        return new Chaos(r1, r2, l1, l2, oracle, metadata, metaLog, metrics,
                router, clients);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Chaos(MvccStorageEngine r1, MvccStorageEngine r2,
                         LockTable locks1, LockTable locks2,
                         TimestampOracle oracle,
                         TransactionMetadataService metadata, Path metaLog,
                         TransactionMetricsRegistry metrics,
                         DistributedTxnRouter router,
                         List<RegionTxnClient> regionClients)
            implements AutoCloseable {
        java.util.function.Function<io.tieringkv.mvcc.ByteKey,
                RegionTxnClient> regionOf() {
            return key -> key.key().length > 0 && key.key()[0] == 'b'
                    ? regionClients.get(1) : regionClients.get(0);
        }

        @Override
        public void close() throws Exception {
            metadata.close();
            ((MemTable) r1.underlying()).close();
            ((MemTable) r2.underlying()).close();
        }
    }

    private interface Fault {
        TxnTransport wrap(TxnTransport delegate);
    }

    private static final class FailAlways implements Fault {
        private final String region;

        private FailAlways(String region) {
            this.region = region;
        }

        @Override
        public TxnTransport wrap(TxnTransport delegate) {
            return (target, regionId, type, payload) -> {
                if (regionId.equals(region)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("partitioned"));
                }
                return delegate.call(target, regionId, type, payload);
            };
        }
    }

    private static final class FailCommitTimes implements Fault {
        private final String region;
        private final int failCount;

        private FailCommitTimes(String region, int failCount) {
            this.region = region;
            this.failCount = failCount;
        }

        @Override
        public TxnTransport wrap(TxnTransport delegate) {
            AtomicInteger remaining = new AtomicInteger(failCount);
            return (target, regionId, type, payload) -> {
                if (regionId.equals(region)
                        && type == io.tieringkv.cluster.rpc.RpcMessageType
                        .TXN_COMMIT
                        && remaining.getAndDecrement() > 0) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("participant down"));
                }
                return delegate.call(target, regionId, type, payload);
            };
        }
    }

    private static final class FailNTimes implements Fault {
        private final String region;
        private final int failCount;

        private FailNTimes(String region, int failCount) {
            this.region = region;
            this.failCount = failCount;
        }

        @Override
        public TxnTransport wrap(TxnTransport delegate) {
            AtomicInteger attempts = new AtomicInteger();
            return (target, regionId, type, payload) -> {
                if (regionId.equals(region)
                        && attempts.getAndIncrement() < failCount) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("not leader"));
                }
                return delegate.call(target, regionId, type, payload);
            };
        }
    }

    private static final class LossyTransport implements Fault {
        private final String region;
        private final int lossPercent;

        private LossyTransport(String region, int lossPercent) {
            this.region = region;
            this.lossPercent = lossPercent;
        }

        @Override
        public TxnTransport wrap(TxnTransport delegate) {
            return (target, regionId, type, payload) -> {
                if (regionId.equals(region)
                        && java.util.concurrent.ThreadLocalRandom.current()
                        .nextInt(100) < lossPercent) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("packet loss"));
                }
                return delegate.call(target, regionId, type, payload);
            };
        }
    }

    private static final class DelayedTransport implements Fault {
        private final String region;
        private final long delayMillis;

        private DelayedTransport(String region, long delayMillis) {
            this.region = region;
            this.delayMillis = delayMillis;
        }

        @Override
        public TxnTransport wrap(TxnTransport delegate) {
            return (target, regionId, type, payload) -> {
                CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> future =
                        delegate.call(target, regionId, type, payload);
                if (!regionId.equals(region)) {
                    return future;
                }
                return future.thenApplyAsync(frame -> frame,
                        CompletableFuture.delayedExecutor(
                                delayMillis, java.util.concurrent.TimeUnit
                                        .MILLISECONDS));
            };
        }
    }

    private static final class FailAtPosition implements Fault {
        private final String region;
        private final int position;

        private FailAtPosition(String region, int position) {
            this.region = region;
            this.position = position;
        }

        @Override
        public TxnTransport wrap(TxnTransport delegate) {
            AtomicInteger calls = new AtomicInteger();
            return (target, regionId, type, payload) -> {
                if (regionId.equals(region)
                        && calls.getAndIncrement() == position) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("injected fault"));
                }
                return delegate.call(target, regionId, type, payload);
            };
        }
    }
}
