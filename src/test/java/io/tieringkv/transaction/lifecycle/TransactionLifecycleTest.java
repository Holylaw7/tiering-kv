package io.tieringkv.transaction.lifecycle;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 事务生命周期（ADR-0088）：TTL / 心跳续约 / 超时自动 abort。 */
class TransactionLifecycleTest {

    @TempDir
    Path dir;

    @Test
    void beginRegistersActive() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        assertThat(fixture.lifecycle.get(txn.txnId()).state())
                .isEqualTo(TxnLifecycleState.ACTIVE);
        assertThat(fixture.lifecycle.activeCount()).isEqualTo(1);
        fixture.close();
    }

    @Test
    void commitMarksCommitted() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        assertThat(fixture.lifecycle.get(txn.txnId()).state())
                .isEqualTo(TxnLifecycleState.COMMITTED);
        assertThat(fixture.lifecycle.activeCount()).isZero();
        fixture.close();
    }

    @Test
    void rollbackMarksRolledBack() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.rollback(txn);
        assertThat(fixture.lifecycle.get(txn.txnId()).state())
                .isEqualTo(TxnLifecycleState.ROLLED_BACK);
        fixture.close();
    }

    @Test
    void ttlCandidateAfterSilence() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        Thread.sleep(20);
        List<TransactionLifecycleManager.TxnHandle> expired =
                fixture.lifecycle.expiredCandidates(
                        System.currentTimeMillis());
        assertThat(expired).isEmpty();
        fixture.close();
    }

    @Test
    void expiredCandidateAfterTtl() throws Exception {
        TransactionLifecycleManager lifecycle = new TransactionLifecycleManager();
        Transaction txn = new Transaction("t1", 1);
        lifecycle.begin(txn, 10, 1000);
        List<TransactionLifecycleManager.TxnHandle> expired =
                lifecycle.expiredCandidates(System.currentTimeMillis() + 100);
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).txn().txnId()).isEqualTo("t1");
    }

    @Test
    void maxDurationExpiresEvenWithHeartbeat() throws Exception {
        TransactionLifecycleManager lifecycle = new TransactionLifecycleManager();
        Transaction txn = new Transaction("t1", 1);
        lifecycle.begin(txn, 60_000, 100);
        lifecycle.heartbeat("t1", System.currentTimeMillis());
        List<TransactionLifecycleManager.TxnHandle> expired =
                lifecycle.expiredCandidates(System.currentTimeMillis() + 200);
        assertThat(expired).hasSize(1);
    }

    @Test
    void heartbeatPreventsTtlExpiry() throws Exception {
        TransactionLifecycleManager lifecycle = new TransactionLifecycleManager();
        Transaction txn = new Transaction("t1", 1);
        lifecycle.begin(txn, 100, 1000);
        long now = System.currentTimeMillis();
        lifecycle.heartbeat("t1", now + 50);
        assertThat(lifecycle.expiredCandidates(now + 100)).isEmpty();
    }

    @Test
    void schedulerAbortsExpiredTxn() throws Exception {
        Fixture fixture = fixture(20, 1000);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        // 手动 prewrite 制造锁
        fixture.regionClients.get(0).prewrite(txn,
                List.of(new io.tieringkv.transaction.rpc.TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false))).join();
        Thread.sleep(50);
        assertThat(fixture.scheduler.scan()).isGreaterThanOrEqualTo(1);
        assertThat(fixture.lifecycle.get(txn.txnId()).state())
                .isEqualTo(TxnLifecycleState.EXPIRED);
        assertThat(fixture.locks1.size()).isZero();
        assertThat(fixture.metrics.snapshot().expiredTotal())
                .isGreaterThanOrEqualTo(1);
        fixture.close();
    }

    @Test
    void heartbeatManagerRefreshesParticipantLocks() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.regionClients.get(0).prewrite(txn,
                List.of(new io.tieringkv.transaction.rpc.TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false))).join();
        io.tieringkv.mvcc.LockRecord before = fixture.locks1.check(bytes("a1"));
        Thread.sleep(5);
        assertThat(fixture.heartbeatManager.heartbeat(
                txn.txnId(), txn.startTS())).isTrue();
        io.tieringkv.mvcc.LockRecord after = fixture.locks1.check(bytes("a1"));
        assertThat(after.createdAtMillis())
                .isGreaterThanOrEqualTo(before.createdAtMillis());
        fixture.close();
    }

    @Test
    void heartbeatUnknownTxnFalse() throws Exception {
        Fixture fixture = fixture();
        assertThat(fixture.heartbeatManager.heartbeat("ghost", 1)).isFalse();
        fixture.close();
    }

    @Test
    void preparedCountTracksPrewrite() throws Exception {
        TransactionLifecycleManager lifecycle = new TransactionLifecycleManager();
        Transaction txn = new Transaction("t1", 1);
        lifecycle.begin(txn, 1000, 1000);
        lifecycle.markPrewrite("t1");
        assertThat(lifecycle.preparedCount()).isEqualTo(1);
        lifecycle.markCommitted("t1");
        assertThat(lifecycle.preparedCount()).isZero();
    }

    @Test
    void expiredTxnNoPermanentLockAfterRecovery() throws Exception {
        Fixture fixture = fixture(20, 1000);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.regionClients.get(0).prewrite(txn,
                List.of(new io.tieringkv.transaction.rpc.TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false))).join();
        Thread.sleep(50);
        fixture.scheduler.scan();
        new io.tieringkv.transaction.lock.LockResolver(
                fixture.metadata, java.util.Map.of("r1",
                fixture.regionClients.get(0)),
                key -> fixture.locks1.check(key) != null,
                new io.tieringkv.transaction.lock.TxnStatusCache(1000))
                .resolve(txn.txnId(), bytes("a1"), txn.startTS());
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    @ParameterizedTest(name = "ttl {0}")
    @ValueSource(longs = {5, 10, 20, 50})
    void parameterizedTtlAborts(long ttlMillis) throws Exception {
        Fixture fixture = fixture(ttlMillis, 1000);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.regionClients.get(0).prewrite(txn,
                List.of(new io.tieringkv.transaction.rpc.TxnMessages.Mutation(
                        bytes("a1"), bytes("va"), false))).join();
        Thread.sleep(ttlMillis + 30);
        fixture.scheduler.scan();
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    @ParameterizedTest(name = "duration {0}")
    @ValueSource(longs = {10, 20, 30})
    void parameterizedMaxDurationAborts(long durationMillis)
            throws Exception {
        Fixture fixture = fixture(60_000, durationMillis);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Thread.sleep(durationMillis + 20);
        fixture.scheduler.scan();
        assertThat(fixture.lifecycle.get(txn.txnId()).state())
                .isEqualTo(TxnLifecycleState.EXPIRED);
        fixture.close();
    }

    private Fixture fixture() throws Exception {
        return fixture(60_000, 300_000);
    }

    private Fixture fixture(long ttlMillis, long maxDurationMillis)
            throws Exception {
        MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
        LockTable l1 = new LockTable();
        LocalTxnTransport t1 = new LocalTxnTransport(
                new TransactionParticipant("r1", r1, l1, ttlMillis));
        TimestampOracle oracle = new TimestampOracle();
        Path metaLog = dir.resolve("meta-" + System.nanoTime() + ".log");
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        metaLog);
        TransactionMetricsRegistry metrics =
                new TransactionMetricsRegistry();
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", t1), key -> true);
        List<RegionTxnClient> clients = List.of(c1);
        TransactionLifecycleManager lifecycle =
                new TransactionLifecycleManager();
        DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                key -> c1, clients, metadata, metrics, lifecycle,
                ttlMillis, maxDurationMillis);
        TxnHeartbeatManager heartbeatManager = new TxnHeartbeatManager(
                lifecycle, clients, ttlMillis, metrics);
        TxnTimeoutScheduler scheduler = new TxnTimeoutScheduler(
                lifecycle, router, metrics);
        return new Fixture(router, lifecycle, heartbeatManager, scheduler,
                metadata, metrics, l1, clients);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(DistributedTxnRouter router,
                           TransactionLifecycleManager lifecycle,
                           TxnHeartbeatManager heartbeatManager,
                           TxnTimeoutScheduler scheduler,
                           TransactionMetadataService metadata,
                           TransactionMetricsRegistry metrics,
                           LockTable locks1,
                           List<RegionTxnClient> regionClients)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            scheduler.close();
            metadata.close();
        }
    }
}
