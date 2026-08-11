package io.tieringkv.transaction;

import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.LockRecord;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 网络 2PC 故障（ADR-0083）：超时/丢包/重试/幂等/无永久锁。 */
class TxnNetworkFailureTest {

    @TempDir
    Path dir;

    @Test
    void prewriteMarksLocked() {
        Local region = region("r1");
        TxnMessages.Response response = region.client().prewrite(
                prewrite("t1", "k", "v")).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(region.participant().state("t1"))
                .isEqualTo(TxnMessages.ParticipantState.LOCKED);
        assertThat(region.locks().size()).isEqualTo(1);
        region.close();
    }

    @Test
    void commitMarksCommitted() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        TxnMessages.Response response = region.client().commit(
                commit("t1", 2, "k", "v")).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(region.participant().state("t1"))
                .isEqualTo(TxnMessages.ParticipantState.COMMITTED);
        assertThat(region.engine().latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(region.locks().size()).isZero();
        region.close();
    }

    @Test
    void rollbackMarksRolledBack() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        TxnMessages.Response response = region.client().rollback(
                rollback("t1")).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(region.participant().state("t1"))
                .isEqualTo(TxnMessages.ParticipantState.ROLLED_BACK);
        assertThat(region.locks().size()).isZero();
        assertThat(region.engine().latestValue(bytes("k"))).isNull();
        region.close();
    }

    @Test
    void duplicatePrewriteIdempotent() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        TxnMessages.Response response = region.client().prewrite(
                prewrite("t1", "k", "v")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.OK);
        assertThat(region.engine().versionCount()).isEqualTo(1);
        region.close();
    }

    @Test
    void duplicateCommitAlready() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        region.client().commit(commit("t1", 2, "k", "v")).join();
        TxnMessages.Response response = region.client().commit(
                commit("t1", 2, "k", "v")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ALREADY);
        assertThat(region.engine().latestValue(bytes("k"))).isEqualTo(bytes("v"));
        region.close();
    }

    @Test
    void rollbackAfterCommitAlready() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        region.client().commit(commit("t1", 2, "k", "v")).join();
        TxnMessages.Response response = region.client().rollback(
                rollback("t1")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ALREADY);
        assertThat(region.engine().latestValue(bytes("k"))).isEqualTo(bytes("v"));
        region.close();
    }

    @Test
    void prewriteConflictReturnsConflict() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        TxnMessages.Response response = region.client().prewrite(
                prewrite("t2", "k", "other")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.CONFLICT);
        region.close();
    }

    @Test
    void commitMissingLockAlready() {
        Local region = region("r1");
        TxnMessages.Response response = region.client().commit(
                commit("t1", 2, "k", "v")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ALREADY);
        assertThat(region.engine().latestValue(bytes("k"))).isNull();
        region.close();
    }

    @Test
    void partialPrewriteRetryNoDuplicate() {
        Local region = region("r1");
        region.client().prewrite(new TxnMessages.Prewrite("t1", 1,
                bytes("k1"), List.of(mut("k1", "v1", false)))).join();
        // 重试：k1 已锁定跳过，k2 补锁
        TxnMessages.Response response = region.client().prewrite(
                new TxnMessages.Prewrite("t1", 1, bytes("k1"),
                        List.of(mut("k1", "v1", false),
                                mut("k2", "v2", false)))).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(region.engine().versionCount()).isEqualTo(2);
        assertThat(region.locks().size()).isEqualTo(2);
        region.close();
    }

    @Test
    void rollbackPartialPrewriteCleansLocks() {
        Local region = region("r1");
        region.client().prewrite(new TxnMessages.Prewrite("t1", 1,
                bytes("k1"), List.of(mut("k1", "v1", false),
                mut("k2", "v2", false)))).join();
        region.client().rollback(rollback("t1")).join();
        assertThat(region.locks().size()).isZero();
        assertThat(region.engine().versionCount()).isZero();
        region.close();
    }

    @Test
    void heartbeatRefreshesLock() throws Exception {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        LockRecord before = region.locks().check(bytes("k"));
        Thread.sleep(5);
        region.client().heartbeat(new TxnMessages.Heartbeat(
                "t1", 1, 120_000)).join();
        LockRecord after = region.locks().check(bytes("k"));
        assertThat(after.createdAtMillis())
                .isGreaterThanOrEqualTo(before.createdAtMillis());
        assertThat(after.ttlMillis()).isEqualTo(120_000);
        region.close();
    }

    @Test
    void heartbeatUnknownTxnAlready() {
        Local region = region("r1");
        TxnMessages.Response response = region.client().heartbeat(
                new TxnMessages.Heartbeat("ghost", 1, 60_000)).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ALREADY);
        region.close();
    }

    @Test
    void clientRetryOnLeaderChange() {
        Local region = region("r1");
        TxnParticipantClient retry = new TxnParticipantClient("n1", "r1",
                new FailOnce(region.transport()));
        TxnMessages.Response response = retry.prewrite(
                prewrite("t1", "k", "v")).join();
        assertThat(response.succeeded()).isTrue();
        region.close();
    }

    @Test
    void clientRetryExhaustedFails() {
        Local region = region("r1");
        TxnParticipantClient failing = new TxnParticipantClient("n1", "r1",
                new AlwaysFail());
        CompletableFuture<TxnMessages.Response> future = failing.prewrite(
                prewrite("t1", "k", "v"));
        assertThatThrownBy(future::join).hasRootCauseInstanceOf(
                IllegalStateException.class);
        region.close();
    }

    @Test
    void transportTimeoutSurfacesError() {
        Local region = region("r1");
        TxnParticipantClient timeout = new TxnParticipantClient("n1", "r1",
                (target, regionId, type, payload) ->
                        CompletableFuture.failedFuture(
                                new java.util.concurrent.TimeoutException("t")));
        assertThatThrownBy(() -> timeout.prewrite(
                prewrite("t1", "k", "v")).join())
                .hasRootCauseInstanceOf(TimeoutException.class);
        region.close();
    }

    @Test
    void routerCommitSingleRegion() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        assertThat(fixture.r1.engine().latestValue(bytes("a1")))
                .isEqualTo(bytes("va"));
        fixture.close();
    }

    @Test
    void routerCommitMultiRegion() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.commit(txn);
        assertThat(fixture.r1.engine().latestValue(bytes("a1")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.r2.engine().latestValue(bytes("b1")))
                .isEqualTo(bytes("vb"));
        fixture.close();
    }

    @Test
    void routerRollbackMultiRegion() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router.rollback(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        assertThat(fixture.r1.engine().latestValue(bytes("a1"))).isNull();
        assertThat(fixture.r2.engine().latestValue(bytes("b1"))).isNull();
        fixture.close();
    }

    @Test
    void routerPrewriteConflictRollsBackAll() throws Exception {
        RouterFixture fixture = routerFixture();
        fixture.r1participant().prewrite(prewrite("other", "a1", "x"));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        assertThatThrownBy(() -> fixture.router.commit(txn))
                .isInstanceOf(RuntimeException.class);
        assertThat(txn.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        assertThat(fixture.r1.engine().latestValue(bytes("a1"))).isNull();
        assertThat(fixture.r2.engine().latestValue(bytes("b1"))).isNull();
        assertThat(fixture.r2.locks().size()).isZero();
        fixture.close();
    }

    @Test
    void routerCommitDecisionDurableOnCommitFailure() throws Exception {
        RouterFixture fixture = routerFixture();
        // r2 走“commit 失败”传输，r1 走本地正常路径
        RouterFixture replaced = replaceR2Transport(fixture,
                new FailingOnCommit(fixture.r2.transport()));
        Transaction txn = replaced.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        assertThatThrownBy(() -> replaced.router.commit(txn))
                .isInstanceOf(RuntimeException.class);
        assertThat(txn.state()).isEqualTo(Transaction.State.PREPARED);
        // 决策已持久化：不得回滚；恢复补完
        DistributedTxnRouter.RecoveryResult result = replaced.router.recover();
        assertThat(result.committed()).isEqualTo(1);
        assertThat(replaced.r1.engine().latestValue(bytes("a1")))
                .isEqualTo(bytes("va"));
        assertThat(replaced.r2.engine().latestValue(bytes("b1")))
                .isEqualTo(bytes("vb"));
        replaced.close();
    }

    @Test
    void routerRecoverPreparedCommits() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        Map<String, List<TxnMessages.Mutation>> byRegion =
                fixture.mutations(txn);
        fixture.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        fixture.regionClients.get(1).prewrite(txn,
                byRegion.get("r2")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        long commitTS = fixture.oracle.nextTimestamp();
        fixture.metadata.prepare(txn.txnId(), commitTS).join();
        DistributedTxnRouter.RecoveryResult result = fixture.router.recover();
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.r1.engine().latestValue(bytes("a1")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.r2.engine().latestValue(bytes("b1")))
                .isEqualTo(bytes("vb"));
        fixture.close();
    }

    @Test
    void routerRecoverRegisteredRollsBack() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<TxnMessages.Mutation>> byRegion =
                fixture.mutations(txn);
        fixture.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        DistributedTxnRouter.RecoveryResult result = fixture.router.recover();
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(fixture.r1.engine().latestValue(bytes("a1"))).isNull();
        assertThat(fixture.r1.locks().size()).isZero();
        fixture.close();
    }

    @Test
    void routerRecoverIdempotent() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<TxnMessages.Mutation>> byRegion =
                fixture.mutations(txn);
        fixture.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        long commitTS = fixture.oracle.nextTimestamp();
        fixture.metadata.prepare(txn.txnId(), commitTS).join();
        fixture.router.recover();
        DistributedTxnRouter.RecoveryResult second = fixture.router.recover();
        assertThat(second.committed()).isZero();
        assertThat(fixture.r1.engine().latestValue(bytes("a1")))
                .isEqualTo(bytes("va"));
        fixture.close();
    }

    @Test
    void participantJournalReplaysAfterRestart() throws Exception {
        Path journalPath = dir.resolve("journal-" + System.nanoTime() + ".log");
        TransactionParticipant first = new TransactionParticipant("r1",
                new MvccStorageEngine(MemTable.create()), new LockTable(),
                60_000, new io.tieringkv.mvcc.PersistentTxnJournal(
                journalPath, new io.tieringkv.mvcc.TxnJournal.InMemory()));
        first.prewrite(prewrite("t1", "k", "v"));
        first.commit(commit("t1", 2, "k", "v"));
        // 重启：同一日志重放 → 事务终态恢复
        io.tieringkv.mvcc.TxnRecoveryReplay.RecoveryResult result =
                new io.tieringkv.mvcc.TxnRecoveryReplay(first.engine(),
                        first.locks()).replay(
                        new io.tieringkv.mvcc.PersistentTxnJournal(
                                journalPath,
                                new io.tieringkv.mvcc.TxnJournal.InMemory()));
        assertThat(result.committed()).isZero(); // 已提交，无待恢复
        assertThat(first.engine().latestValue(bytes("k"))).isEqualTo(bytes("v"));
        ((MemTable) first.engine().underlying()).close();
    }

    @Test
    void codecPrewriteRoundTrip() {
        TxnMessages.Prewrite request = prewrite("t1", "k", "v");
        TxnMessages.Prewrite decoded = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodePrewrite(
                io.tieringkv.transaction.rpc.TxnRpcCodec
                        .encodePrewrite(request));
        assertThat(decoded.txnId()).isEqualTo("t1");
        assertThat(decoded.mutations()).hasSize(1);
    }

    @Test
    void codecCommitRoundTrip() {
        TxnMessages.Commit decoded = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodeCommit(
                io.tieringkv.transaction.rpc.TxnRpcCodec.encodeCommit(
                        commit("t1", 2, "k", "v")));
        assertThat(decoded.commitTS()).isEqualTo(2);
    }

    @Test
    void codecRollbackRoundTrip() {
        TxnMessages.Rollback decoded = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodeRollback(
                io.tieringkv.transaction.rpc.TxnRpcCodec.encodeRollback(
                        rollback("t1")));
        assertThat(decoded.txnId()).isEqualTo("t1");
    }

    @Test
    void codecHeartbeatRoundTrip() {
        TxnMessages.Heartbeat decoded = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodeHeartbeat(
                io.tieringkv.transaction.rpc.TxnRpcCodec.encodeHeartbeat(
                        new TxnMessages.Heartbeat("t1", 1, 60_000)));
        assertThat(decoded.ttlMillis()).isEqualTo(60_000);
    }

    @Test
    void codecResponseRoundTrip() {
        TxnMessages.Response decoded = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodeResponse(
                io.tieringkv.transaction.rpc.TxnRpcCodec.encodeResponse(
                        TxnMessages.Response.conflict("boom")));
        assertThat(decoded.status()).isEqualTo(TxnMessages.Status.CONFLICT);
        assertThat(decoded.message()).isEqualTo("boom");
    }

    @Test
    void codecNullValueDeleteRoundTrip() {
        TxnMessages.Prewrite request = new TxnMessages.Prewrite("t1", 1,
                bytes("k"), List.of(mut("k", null, true)));
        TxnMessages.Prewrite decoded = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodePrewrite(
                io.tieringkv.transaction.rpc.TxnRpcCodec
                        .encodePrewrite(request));
        assertThat(decoded.mutations().get(0).value()).isNull();
        assertThat(decoded.mutations().get(0).deleted()).isTrue();
    }

    @Test
    void codecInvalidResponseStatus() {
        TxnMessages.Response decoded = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodeResponse(new byte[]{(byte) 99});
        assertThat(decoded.status()).isEqualTo(TxnMessages.Status.ERROR);
    }

    @Test
    void participantStateUnknownForUnregistered() {
        Local region = region("r1");
        assertThat(region.participant().state("ghost")).isNull();
        region.close();
    }

    @Test
    void concurrentPrewriteSameTxnIdempotent() throws Exception {
        Local region = region("r1");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    TxnMessages.Response response = region.client().prewrite(
                            prewrite("t1", "k", "v")).join();
                    if (!response.succeeded()) {
                        failed.set(true);
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
            worker.join(30_000);
        }
        assertThat(failed).isFalse();
        assertThat(region.engine().versionCount()).isEqualTo(1);
        region.close();
    }

    @Test
    void concurrentCommitSameTxnIdempotent() throws Exception {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    TxnMessages.Response response = region.client().commit(
                            commit("t1", 2, "k", "v")).join();
                    if (!response.succeeded()) {
                        failed.set(true);
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
            worker.join(30_000);
        }
        assertThat(failed).isFalse();
        assertThat(region.engine().latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(region.locks().size()).isZero();
        region.close();
    }

    @Test
    void routerBeginUniqueTimestamps() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction a = fixture.router.begin();
        Transaction b = fixture.router.begin();
        assertThat(b.startTS()).isGreaterThan(a.startTS());
        fixture.close();
    }

    @Test
    void emptyMutationsPrewriteOk() {
        Local region = region("r1");
        TxnMessages.Response response = region.client().prewrite(
                new TxnMessages.Prewrite("t1", 1, bytes("k"), List.of()))
                .join();
        assertThat(response.succeeded()).isTrue();
        region.close();
    }

    @Test
    void deleteMutationCommitHidesOldValue() {
        Local region = region("r1");
        region.engine().putVersion(bytes("k"), bytes("old"), 0, 1,
                io.tieringkv.mvcc.WriteType.PUT);
        region.client().prewrite(new TxnMessages.Prewrite("t1", 2,
                bytes("k"), List.of(mut("k", null, true)))).join();
        region.client().commit(new TxnMessages.Commit("t1", 2, 3,
                bytes("k"), List.of(mut("k", null, true)))).join();
        assertThat(region.engine().latestValue(bytes("k"))).isNull();
        region.close();
    }

    @Test
    void lockRefreshWrongTxnNoop() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        LockRecord before = region.locks().check(bytes("k"));
        assertThat(region.locks().refresh(bytes("k"), "other",
                System.currentTimeMillis(), 60_000)).isFalse();
        assertThat(region.locks().check(bytes("k")).createdAtMillis())
                .isEqualTo(before.createdAtMillis());
        region.close();
    }

    @Test
    void duplicateRollbackAlready() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        region.client().rollback(rollback("t1")).join();
        TxnMessages.Response response = region.client().rollback(
                rollback("t1")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ALREADY);
        region.close();
    }

    @Test
    void prewriteAfterCommitAlready() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        region.client().commit(commit("t1", 2, "k", "v")).join();
        TxnMessages.Response response = region.client().prewrite(
                prewrite("t1", "k", "v")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ALREADY);
        region.close();
    }

    @Test
    void commitAfterRollbackConflict() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        region.client().rollback(rollback("t1")).join();
        TxnMessages.Response response = region.client().commit(
                commit("t1", 2, "k", "v")).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.CONFLICT);
        region.close();
    }

    @Test
    void randomNetworkLossEventuallySucceeds() {
        Local region = region("r1");
        TxnParticipantClient lossy = new TxnParticipantClient("n1", "r1",
                new LossyTransport(region.transport(), 10));
        TxnMessages.Response response = lossy.prewrite(
                prewrite("t1", "k", "v")).join();
        assertThat(response.succeeded()).isTrue();
        region.close();
    }

    @Test
    void responseDecodeEmptyPayload() {
        TxnMessages.Response response = io.tieringkv.transaction.rpc
                .TxnRpcCodec.decodeResponse(new byte[0]);
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ERROR);
    }

    @Test
    void prewriteManyMutations() {
        Local region = region("r1");
        List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            mutations.add(mut("k" + i, "v" + i, false));
        }
        TxnMessages.Response response = region.client().prewrite(
                new TxnMessages.Prewrite("t1", 1, bytes("k0"),
                        mutations)).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(region.locks().size()).isEqualTo(100);
        region.close();
    }

    @Test
    void routerBeginStateActive() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        assertThat(txn.state()).isEqualTo(Transaction.State.ACTIVE);
        fixture.close();
    }

    @Test
    void routerRollbackWithoutMetadata() throws Exception {
        Local r1 = region("r1");
        TimestampOracle oracle = new TimestampOracle();
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", r1.transport()),
                key -> true);
        DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                key -> c1, List.of(c1), null, null);
        Transaction txn = router.begin();
        txn.put(bytes("k"), bytes("v"));
        router.rollback(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        r1.close();
    }

    @Test
    void routerCommitSingleRegionDelete() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction set = fixture.router.begin();
        set.put(bytes("a1"), bytes("va"));
        fixture.router.commit(set);
        Transaction del = fixture.router.begin();
        del.delete(bytes("a1"));
        fixture.router.commit(del);
        assertThat(fixture.r1.engine().latestValue(bytes("a1"))).isNull();
        fixture.close();
    }

    @Test
    void participantStateAfterDuplicateCommit() {
        Local region = region("r1");
        region.client().prewrite(prewrite("t1", "k", "v")).join();
        region.client().commit(commit("t1", 2, "k", "v")).join();
        region.client().commit(commit("t1", 2, "k", "v")).join();
        assertThat(region.participant().state("t1"))
                .isEqualTo(TxnMessages.ParticipantState.COMMITTED);
        region.close();
    }

    @Test
    void transportUnknownTargetSurfacesError() {
        Local region = region("r1");
        TxnParticipantClient unknown = new TxnParticipantClient("ghost",
                "r1", new UnknownTarget());
        assertThatThrownBy(() -> unknown.prewrite(
                prewrite("t1", "k", "v")).join())
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        region.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void parameterizedRetryBudget(int failures) {
        Local region = region("r1");
        TxnParticipantClient client = new TxnParticipantClient("n1", "r1",
                new FailNTimes(region.transport(), failures));
        TxnMessages.Response response = client.prewrite(
                prewrite("t1", "k", "v")).join();
        assertThat(response.succeeded()).isTrue();
        region.close();
    }

    private static final class LossyTransport implements TxnTransport {
        private final TxnTransport delegate;
        private final int lossPercent;

        private LossyTransport(TxnTransport delegate, int lossPercent) {
            this.delegate = delegate;
            this.lossPercent = lossPercent;
        }

        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            if (java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(100) < lossPercent) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("packet loss"));
            }
            return delegate.call(target, regionId, type, payload);
        }
    }

    private static final class UnknownTarget implements TxnTransport {
        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unknown peer"));
        }
    }

    private static final class FailNTimes implements TxnTransport {
        private final TxnTransport delegate;
        private final int failCount;
        private final java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();

        private FailNTimes(TxnTransport delegate, int failCount) {
            this.delegate = delegate;
            this.failCount = failCount;
        }

        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            if (attempts.getAndIncrement() < failCount) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("not leader"));
            }
            return delegate.call(target, regionId, type, payload);
        }
    }

    // ---------- harness ----------

    private Local region(String id) {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionParticipant participant = new TransactionParticipant(
                id, engine, locks, 60_000);
        LocalTxnTransport transport = new LocalTxnTransport(participant);
        TxnParticipantClient client = new TxnParticipantClient(
                "n1", id, transport);
        return new Local(id, participant, engine, locks, transport, client);
    }

    private RouterFixture routerFixture() throws Exception {
        return routerFixture(null);
    }

    private RouterFixture routerFixture(TxnTransport fault)
            throws Exception {
        Local r1 = region("r1");
        Local r2 = region("r2");
        TxnTransport r2Transport = fault == null
                ? r2.transport() : fault;
        TimestampOracle oracle = new TimestampOracle();
        Path metaLog = dir.resolve("meta-" + System.nanoTime() + ".log");
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        metaLog);
        TransactionMetricsRegistry metrics =
                new TransactionMetricsRegistry();
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", r1.transport()),
                key -> key.key().length > 0 && key.key()[0] == 'a');
        RegionTxnClient c2 = new RegionTxnClient("r2",
                new TxnParticipantClient("n2", "r2", r2Transport),
                key -> key.key().length > 0 && key.key()[0] == 'b');
        List<RegionTxnClient> clients = List.of(c1, c2);
        DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                key -> key.key().length > 0 && key.key()[0] == 'b' ? c2 : c1,
                clients, metadata, metrics);
        return new RouterFixture(r1, r2, oracle, metadata, metrics, router,
                clients);
    }

    private RouterFixture replaceR2Transport(RouterFixture fixture,
                                             TxnTransport transport) {
        RegionTxnClient c1 = fixture.regionClients.get(0);
        RegionTxnClient c2 = new RegionTxnClient("r2",
                new TxnParticipantClient("n2", "r2", transport),
                key -> key.key().length > 0 && key.key()[0] == 'b');
        List<RegionTxnClient> clients = List.of(c1, c2);
        DistributedTxnRouter router = new DistributedTxnRouter(
                fixture.oracle,
                key -> key.key().length > 0 && key.key()[0] == 'b' ? c2 : c1,
                clients, fixture.metadata, fixture.metrics);
        return new RouterFixture(fixture.r1, fixture.r2, fixture.oracle,
                fixture.metadata, fixture.metrics, router, clients);
    }

    private static TxnMessages.Prewrite prewrite(String txnId, String key,
                                                 String value) {
        return new TxnMessages.Prewrite(txnId, 1, bytes(key),
                List.of(mut(key, value, false)));
    }

    private static TxnMessages.Commit commit(String txnId, long commitTS,
                                             String key, String value) {
        return new TxnMessages.Commit(txnId, 1, commitTS, bytes(key),
                List.of(mut(key, value, false)));
    }

    private static TxnMessages.Rollback rollback(String txnId) {
        return new TxnMessages.Rollback(txnId, 1, new byte[]{0});
    }

    private static TxnMessages.Mutation mut(String key, String value,
                                            boolean deleted) {
        return new TxnMessages.Mutation(bytes(key),
                value == null ? null : bytes(value), deleted);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Local(String regionId,
                         TransactionParticipant participant,
                         MvccStorageEngine engine, LockTable locks,
                         LocalTxnTransport transport,
                         TxnParticipantClient client) implements AutoCloseable {
        @Override
        public void close() {
            ((MemTable) engine.underlying()).close();
        }
    }

    private record RouterFixture(Local r1, Local r2, TimestampOracle oracle,
                                 TransactionMetadataService metadata,
                                 TransactionMetricsRegistry metrics,
                                 DistributedTxnRouter router,
                                 List<RegionTxnClient> regionClients)
            implements AutoCloseable {
        TransactionParticipant r1participant() {
            return r1.participant();
        }

        LockTable locks1() {
            return r1.locks();
        }

        Map<String, List<TxnMessages.Mutation>> mutations(Transaction txn) {
            Map<String, List<TxnMessages.Mutation>> byRegion =
                    new LinkedHashMap<>();
            for (ByteKey key : txn.writeKeys()) {
                String region = key.key().length > 0 && key.key()[0] == 'b'
                        ? "r2" : "r1";
                byRegion.computeIfAbsent(region, ignored -> new ArrayList<>())
                        .add(new TxnMessages.Mutation(key.key(),
                                txn.writeValue(key), false));
            }
            return byRegion;
        }

        @Override
        public void close() throws Exception {
            metadata.close();
            r1.close();
            r2.close();
        }
    }

    /** 首次失败（leader 变更），之后透传。 */
    private static final class FailOnce implements TxnTransport {
        private final TxnTransport delegate;
        private boolean failed;

        private FailOnce(TxnTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            if (!failed) {
                failed = true;
                return CompletableFuture.failedFuture(
                        new IllegalStateException("not leader"));
            }
            return delegate.call(target, regionId, type, payload);
        }
    }

    private static final class AlwaysFail implements TxnTransport {
        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("raft down"));
        }
    }

    /** commit 失败（模拟 apply 阶段故障），其余透传。 */
    private static final class FailingOnCommit implements TxnTransport {
        private final TxnTransport delegate;
        private final java.util.concurrent.atomic.AtomicInteger failuresLeft =
                new java.util.concurrent.atomic.AtomicInteger(4);

        private FailingOnCommit(TxnTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            // 连续失败 3 次（耗尽客户端重试），之后恢复
            if (type == io.tieringkv.cluster.rpc.RpcMessageType.TXN_COMMIT
                    && failuresLeft.getAndDecrement() > 0) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("participant down"));
            }
            return delegate.call(target, regionId, type, payload);
        }
    }
}
