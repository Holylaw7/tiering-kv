package io.tieringkv.transaction.metadata;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.LocalTxnTransport;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.TxnParticipantClient;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 元数据决策排序（ADR-0087）：decisionIndex + Raft-first + 崩溃窗口。 */
class MetadataOrderingTest {

    @TempDir
    Path dir;

    @Test
    void decisionIndexRecordedOnPrepare() throws Exception {
        Fixture fixture = fixture();
        fixture.metadata.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.metadata.prepare("t1", 9).join();
        assertThat(fixture.metadata.state().get("t1").decisionIndex())
                .isGreaterThanOrEqualTo(0);
        fixture.close();
    }

    @Test
    void raftFirstNoStateOnProposeFailure() throws Exception {
        Path log = dir.resolve("fail.log");
        TransactionMetadataService service =
                new TransactionMetadataService(
                        command -> CompletableFuture.failedFuture(
                                new IllegalStateException("raft down")),
                        log);
        assertThatThrownBy(() -> service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(service.state().size()).isZero();
        service.close();
    }

    @Test
    void mirrorWrittenOnlyAfterRaftSuccess() throws Exception {
        Path log = dir.resolve("mirror.log");
        TransactionMetadataService service =
                new TransactionMetadataService(
                        command -> CompletableFuture.failedFuture(
                                new IllegalStateException("raft down")),
                        log);
        try {
            service.register("t1", bytes("a"), 1,
                    Map.of("r1", List.of(mut("a", "v", false)))).join();
        } catch (RuntimeException ignored) {
            // 预期失败
        }
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(log, command -> CompletableFuture.completedFuture(1L));
        assertThat(recovered.state().size()).isZero();
        recovered.close();
        service.close();
    }

    @Test
    void recoverFromRaftReplaysOrdered() throws Exception {
        Path log = dir.resolve("raft-replay.log");
        List<byte[]> raftCommands = List.of(
                TxnMetaCodec.encode(TxnMetaCommand.register(
                        "t1", bytes("a"), 1,
                        Map.of("r1", List.of(mut("a", "v", false))))),
                TxnMetaCodec.encode(TxnMetaCommand.prepare("t1", 9)),
                TxnMetaCodec.encode(TxnMetaCommand.commit("t1", 9)));
        TransactionMetadataService recovered =
                TransactionMetadataService.recoverFromRaft(
                        raftCommands, command ->
                                CompletableFuture.completedFuture(1L), log);
        TxnMetaEntry entry = recovered.state().get("t1");
        assertThat(entry.state()).isEqualTo(TxnMetaEntry.State.COMMITTED);
        assertThat(entry.decisionIndex()).isEqualTo(2);
        recovered.close();
    }

    @Test
    void decisionBeforeParticipantCommit() throws Exception {
        Fixture fixture = fixture();
        AtomicLong decisionIndexAtCommit = new AtomicLong(-1);
        // 拦截 participant commit：断言决策索引已先于提交持久化
        RegionTxnClient original = fixture.regionClients.get(0);
        RegionTxnClient tracking = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1",
                        new TrackingTransport(
                                fixture.r1, fixture.locks1,
                                decisionIndexAtCommit,
                                fixture.metadata)),
                key -> true);
        DistributedTxnRouter router = new DistributedTxnRouter(
                fixture.oracle, key -> tracking, List.of(tracking),
                fixture.metadata, fixture.metrics);
        Transaction txn = router.begin();
        txn.put(bytes("a1"), bytes("va"));
        router.commit(txn);
        assertThat(decisionIndexAtCommit.get()).isGreaterThanOrEqualTo(0);
        fixture.close();
    }

    @Test
    void duplicateCommitIdempotent() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        fixture.router.recover();
        fixture.router.recover();
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    @Test
    void crashBetweenDecisionAndCommitRecovers() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<TxnMessages.Mutation>> byRegion = Map.of("r1",
                List.of(mut("a1", "va", false)));
        fixture.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        fixture.metadata.prepare(txn.txnId(), 9).join();
        fixture.metadata.close();
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter restarted = new DistributedTxnRouter(
                fixture.oracle, key -> fixture.regionClients.get(0),
                fixture.regionClients, recovered, fixture.metrics);
        assertThat(restarted.recover().committed()).isEqualTo(1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        recovered.close();
        fixture.close();
    }

    @Test
    void decisionIndexMonotonic() throws Exception {
        Path log = dir.resolve("mono.log");
        java.util.concurrent.atomic.AtomicLong index =
                new java.util.concurrent.atomic.AtomicLong();
        TransactionMetadataService service = new TransactionMetadataService(
                command -> CompletableFuture.completedFuture(
                        index.incrementAndGet()), log);
        service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        service.prepare("t1", 1).join();
        service.commit("t1", 2).join();
        service.register("t2", bytes("b"), 3,
                Map.of("r1", List.of(mut("b", "v", false)))).join();
        service.prepare("t2", 4).join();
        long first = service.state().get("t1").decisionIndex();
        long second = service.state().get("t2").decisionIndex();
        assertThat(second).isGreaterThan(first);
        service.close();
    }

    @Test
    void committedWithLocksRecovered() throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<TxnMessages.Mutation>> byRegion = Map.of("r1",
                List.of(mut("a1", "va", false)));
        fixture.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        fixture.metadata.prepare(txn.txnId(), 9).join();
        fixture.metadata.commit(txn.txnId(), 9).join();
        // 崩溃于 metadata COMMITTED 之后、participant commit 之前
        fixture.metadata.close();
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter restarted = new DistributedTxnRouter(
                fixture.oracle, key -> fixture.regionClients.get(0),
                fixture.regionClients, recovered, fixture.metrics);
        assertThat(restarted.recover().committed()).isEqualTo(1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.locks1.size()).isZero();
        recovered.close();
        fixture.close();
    }

    @ParameterizedTest(name = "commands {0}")
    @ValueSource(ints = {1, 4, 8, 16})
    void parameterizedRaftReplay(int commandCount) throws Exception {
        Path log = dir.resolve("raft-" + System.nanoTime() + ".log");
        java.util.ArrayList<byte[]> commands = new java.util.ArrayList<>();
        for (int i = 0; i < commandCount; i++) {
            commands.add(TxnMetaCodec.encode(TxnMetaCommand.register(
                    "t" + i, bytes("k"), i,
                    Map.of("r1", List.of(mut("k", "v", false))))));
        }
        TransactionMetadataService recovered =
                TransactionMetadataService.recoverFromRaft(
                        commands, command ->
                                CompletableFuture.completedFuture(1L), log);
        assertThat(recovered.state().size()).isEqualTo(commandCount);
        assertThat(recovered.state().get("t" + (commandCount - 1))
                .decisionIndex()).isEqualTo(commandCount - 1);
        recovered.close();
    }

    @ParameterizedTest(name = "steps {0}")
    @ValueSource(ints = {0, 1, 2})
    void parameterizedCrashAfterStep(int steps) throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<TxnMessages.Mutation>> byRegion = Map.of("r1",
                List.of(mut("a1", "va", false)));
        fixture.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        if (steps >= 1) {
            fixture.metadata.prepare(txn.txnId(), 9).join();
        }
        if (steps >= 2) {
            fixture.metadata.commit(txn.txnId(), 9).join();
        }
        fixture.metadata.close();
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter restarted = new DistributedTxnRouter(
                fixture.oracle, key -> fixture.regionClients.get(0),
                fixture.regionClients, recovered, fixture.metrics);
        DistributedTxnRouter.RecoveryResult result = restarted.recover();
        if (steps >= 1) {
            assertThat(result.committed()).isEqualTo(1);
            assertThat(fixture.r1.latestValue(bytes("a1")))
                    .isEqualTo(bytes("va"));
        } else {
            assertThat(result.rolledBack()).isEqualTo(1);
            assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
        }
        recovered.close();
        fixture.close();
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 2, 4, 8})
    void parameterizedDecisionIndices(int txnCount) throws Exception {
        Path log = dir.resolve("idx-" + System.nanoTime() + ".log");
        java.util.concurrent.atomic.AtomicLong index =
                new java.util.concurrent.atomic.AtomicLong();
        TransactionMetadataService service = new TransactionMetadataService(
                command -> CompletableFuture.completedFuture(
                        index.incrementAndGet()), log);
        long previous = -1;
        for (int i = 0; i < txnCount; i++) {
            service.register("t" + i, bytes("k"), i,
                    Map.of("r1", List.of(mut("k", "v", false)))).join();
            service.prepare("t" + i, i + 1).join();
            long current = service.state().get("t" + i).decisionIndex();
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
        service.close();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 2, 5})
    void parameterizedDuplicateCommit(int rounds) throws Exception {
        Fixture fixture = fixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        for (int i = 0; i < rounds; i++) {
            assertThat(fixture.router.recover().committed()).isZero();
        }
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        fixture.close();
    }

    private Fixture fixture() throws Exception {
        MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
        LockTable l1 = new LockTable();
        LocalTxnTransport t1 = new LocalTxnTransport(
                new TransactionParticipant("r1", r1, l1, 60_000));
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
        DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                key -> c1, clients, metadata, metrics);
        return new Fixture(r1, l1, oracle, metadata, metaLog, metrics, router,
                clients);
    }

    private static TxnMessages.Mutation mut(String key, String value,
                                            boolean deleted) {
        return new TxnMessages.Mutation(bytes(key),
                value == null ? null : bytes(value), deleted);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(MvccStorageEngine r1, LockTable locks1,
                           TimestampOracle oracle,
                           TransactionMetadataService metadata,
                           Path metaLog, TransactionMetricsRegistry metrics,
                           DistributedTxnRouter router,
                           List<RegionTxnClient> regionClients)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            metadata.close();
            ((MemTable) r1.underlying()).close();
        }
    }

    /** 追踪 participant commit 时元数据决策索引。 */
    private static final class TrackingTransport
            implements io.tieringkv.transaction.router.TxnTransport {
        private final MvccStorageEngine engine;
        private final LockTable locks;
        private final AtomicLong decisionIndexAtCommit;
        private final TransactionMetadataService metadata;

        private TrackingTransport(MvccStorageEngine engine, LockTable locks,
                                  AtomicLong decisionIndexAtCommit,
                                  TransactionMetadataService metadata) {
            this.engine = engine;
            this.locks = locks;
            this.decisionIndexAtCommit = decisionIndexAtCommit;
            this.metadata = metadata;
        }

        @Override
        public CompletableFuture<io.tieringkv.cluster.rpc.RpcFrame> call(
                String target, String regionId,
                io.tieringkv.cluster.rpc.RpcMessageType type, byte[] payload) {
            if (type == io.tieringkv.cluster.rpc.RpcMessageType.TXN_COMMIT) {
                io.tieringkv.transaction.rpc.TxnMessages.Commit commit =
                        io.tieringkv.transaction.rpc.TxnRpcCodec.decodeCommit(
                                payload);
                TxnMetaEntry entry = metadata.state().get(commit.txnId());
                if (entry != null) {
                    decisionIndexAtCommit.set(entry.decisionIndex());
                }
            }
            io.tieringkv.transaction.participant.TransactionParticipant
                    participant = new TransactionParticipant(
                    regionId, engine, locks, 60_000);
            io.tieringkv.cluster.rpc.RpcFrame frame = new io.tieringkv.cluster
                    .rpc.RpcFrame(io.tieringkv.cluster.rpc.RequestId
                    .next().value(), type, payload);
            return CompletableFuture.completedFuture(
                    new io.tieringkv.transaction.rpc
                            .TxnParticipantRpcHandler(participant)
                            .handle(frame, regionId, payload));
        }
    }
}
