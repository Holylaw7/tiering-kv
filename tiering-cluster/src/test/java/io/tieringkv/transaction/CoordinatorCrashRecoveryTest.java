package io.tieringkv.transaction;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.metadata.TransactionMetadataState;
import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TxnMetaEntry;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 协调器崩溃恢复（ADR-0084）：元数据 Raft + 本地日志 + 续跑。 */
class CoordinatorCrashRecoveryTest {

    @TempDir
    Path dir;

    @Test
    void registerThenStateRegistered() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        assertThat(fixture.state().get("t1").state())
                .isEqualTo(TxnMetaEntry.State.REGISTERED);
        fixture.close();
    }

    @Test
    void prepareThenStatePrepared() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.service.prepare("t1", 5).join();
        assertThat(fixture.state().get("t1").state())
                .isEqualTo(TxnMetaEntry.State.PREPARED);
        assertThat(fixture.state().get("t1").commitTS()).isEqualTo(5);
        fixture.close();
    }

    @Test
    void commitThenStateCommitted() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.service.prepare("t1", 5).join();
        fixture.service.commit("t1", 5).join();
        assertThat(fixture.state().get("t1").state())
                .isEqualTo(TxnMetaEntry.State.COMMITTED);
        assertThat(fixture.state().pending()).isEmpty();
        fixture.close();
    }

    @Test
    void rollbackThenStateRolledBack() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.service.rollback("t1").join();
        assertThat(fixture.state().get("t1").state())
                .isEqualTo(TxnMetaEntry.State.ROLLED_BACK);
        fixture.close();
    }

    @Test
    void crashAfterPrepareRecoversCommit() throws Exception {
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
        fixture.metadata.close(); // 崩溃

        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter recoveredRouter = new DistributedTxnRouter(
                fixture.oracle, fixture.regionOf(), fixture.regionClients,
                recovered, fixture.metrics);
        DistributedTxnRouter.RecoveryResult result = recoveredRouter.recover();
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.r2.latestValue(bytes("b1"))).isEqualTo(bytes("vb"));
        recovered.close();
        fixture.close();
    }

    @Test
    void crashAfterRegisterRollsBack() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        Map<String, List<TxnMessages.Mutation>> byRegion =
                fixture.mutations(txn);
        fixture.regionClients.get(0).prewrite(txn,
                byRegion.get("r1")).join();
        fixture.metadata.register(txn.txnId(), bytes("a1"),
                txn.startTS(), byRegion).join();
        fixture.metadata.close();

        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter recoveredRouter = new DistributedTxnRouter(
                fixture.oracle, fixture.regionOf(), fixture.regionClients,
                recovered, fixture.metrics);
        DistributedTxnRouter.RecoveryResult result = recoveredRouter.recover();
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
        assertThat(fixture.l1.size()).isZero();
        recovered.close();
        fixture.close();
    }

    @Test
    void crashAfterCommitNoPending() throws Exception {
        RouterFixture fixture = routerFixture();
        Transaction txn = fixture.router.begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router.commit(txn);
        fixture.metadata.close();
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        assertThat(recovered.state().pending()).isEmpty();
        recovered.close();
        fixture.close();
    }

    @Test
    void recoverFromEmptyLog() throws Exception {
        Path log = dir.resolve("empty.log");
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(log, command -> CompletableFuture.completedFuture(1L));
        assertThat(recovered.state().size()).isZero();
        recovered.close();
    }

    @Test
    void recoverFromTruncatedTail() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.service.prepare("t1", 5).join();
        fixture.service.close();
        // 追加半条损坏记录（崩溃写一半）
        Files.write(fixture.log, new byte[]{0, 0, 0, 9, 1},
                java.nio.file.StandardOpenOption.APPEND);
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.log, command ->
                        CompletableFuture.completedFuture(1L));
        assertThat(recovered.state().get("t1").state())
                .isEqualTo(TxnMetaEntry.State.PREPARED);
        recovered.close();
    }

    @Test
    void rollbackAfterPrepare() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.service.prepare("t1", 5).join();
        fixture.service.rollback("t1").join();
        assertThat(fixture.state().get("t1").state())
                .isEqualTo(TxnMetaEntry.State.ROLLED_BACK);
        fixture.close();
    }

    @Test
    void pendingExcludesTerminal() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.service.prepare("t1", 5).join();
        fixture.service.commit("t1", 5).join();
        fixture.service.register("t2", bytes("b"), 2,
                Map.of("r2", List.of(mut("b", "v", false)))).join();
        assertThat(fixture.state().pending()).hasSize(1);
        assertThat(fixture.state().pending().get(0).txnId()).isEqualTo("t2");
        fixture.close();
    }

    @Test
    void metadataPreservesMutations() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)),
                        "r2", List.of(mut("b", "w", false)))).join();
        TxnMetaEntry entry = fixture.state().get("t1");
        assertThat(entry.regionMutations().get("r1")).hasSize(1);
        assertThat(entry.regionMutations().get("r2")).hasSize(1);
        fixture.close();
    }

    @Test
    void metadataPreservesCommitTs() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        fixture.service.prepare("t1", 42).join();
        assertThat(fixture.state().get("t1").commitTS()).isEqualTo(42);
        fixture.close();
    }

    @Test
    void metadataPreservesPrimary() throws Exception {
        ServiceFixture fixture = service();
        fixture.service.register("t1", bytes("primary"), 1,
                Map.of("r1", List.of(mut("a", "v", false)))).join();
        assertThat(fixture.state().get("t1").primary())
                .isEqualTo(bytes("primary"));
        fixture.close();
    }

    @Test
    void concurrentProposeSerialized() throws Exception {
        ServiceFixture fixture = service();
        int threads = 8;
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    fixture.service.register("t" + writer, bytes("a"),
                            writer, Map.of("r1",
                                    List.of(mut("a", "v", false)))).join();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        assertThat(fixture.state().size()).isEqualTo(threads);
        fixture.close();
    }

    @Test
    void metadataLogInvalidLengthThrows() throws Exception {
        Path log = dir.resolve("bad.log");
        Files.write(log, new byte[]{0, 0, 0, 0});
        assertThatThrownBy(() -> TransactionMetadataService.recover(
                log, command -> CompletableFuture.completedFuture(1L)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void recoverAfterCoordinatorRestartContinuesCommit() throws Exception {
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
        fixture.metadata.close();
        // 重启后同一 router 配置续跑
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.metaLog, command ->
                        CompletableFuture.completedFuture(1L));
        DistributedTxnRouter restarted = new DistributedTxnRouter(
                fixture.oracle, fixture.regionOf(), fixture.regionClients,
                recovered, fixture.metrics);
        assertThat(restarted.recover().committed()).isEqualTo(1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        recovered.close();
        fixture.close();
    }

    @Test
    void metadataCodecRoundTrip() {
        TxnMetaCommand command = TxnMetaCommand.register("t1", bytes("a"), 1,
                Map.of("r1", List.of(mut("a", "v", false))));
        TxnMetaCommand decoded = TxnMetaCodec.decode(
                TxnMetaCodec.encode(command));
        assertThat(decoded.txnId()).isEqualTo("t1");
        assertThat(decoded.regionMutations().get("r1")).hasSize(1);
    }

    @Test
    void metadataCodecPrepareRoundTrip() {
        TxnMetaCommand command = TxnMetaCommand.prepare("t1", 42);
        TxnMetaCommand decoded = TxnMetaCodec.decode(
                TxnMetaCodec.encode(command));
        assertThat(decoded.type()).isEqualTo(TxnMetaCommand.Type.PREPARE);
        assertThat(decoded.commitTS()).isEqualTo(42);
    }

    @Test
    void metadataCodecRollbackRoundTrip() {
        TxnMetaCommand command = TxnMetaCommand.rollback("t1");
        TxnMetaCommand decoded = TxnMetaCodec.decode(
                TxnMetaCodec.encode(command));
        assertThat(decoded.type()).isEqualTo(TxnMetaCommand.Type.ROLLBACK);
    }

    @Test
    void metadataStateApplyUnknownTxnNoop() {
        TransactionMetadataState state = new TransactionMetadataState();
        state.apply(TxnMetaCommand.prepare("ghost", 1));
        assertThat(state.get("ghost")).isNull();
    }

    @Test
    void recoverMetricsRecorded() throws Exception {
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
        DistributedTxnRouter.RecoveryResult result = fixture.router.recover();
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.metrics.snapshot().recoveryTxn()).isEqualTo(1);
        assertThat(fixture.metrics.snapshot().recoveryTimeMs())
                .isGreaterThanOrEqualTo(0);
        fixture.close();
    }

    @ParameterizedTest(name = "mutations {0}")
    @ValueSource(ints = {1, 3, 5, 10})
    void parameterizedMutationRecovery(int mutationCount) throws Exception {
        ServiceFixture fixture = service();
        Map<String, java.util.List<TxnMessages.Mutation>> byRegion =
                new LinkedHashMap<>();
        java.util.List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (int i = 0; i < mutationCount; i++) {
            mutations.add(mut("k" + i, "v" + i, false));
        }
        byRegion.put("r1", mutations);
        fixture.service.register("t1", bytes("k0"), 1, byRegion).join();
        fixture.service.prepare("t1", 9).join();
        fixture.service.close();
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.log, command ->
                        CompletableFuture.completedFuture(1L));
        assertThat(recovered.state().get("t1").regionMutations()
                .get("r1")).hasSize(mutationCount);
        assertThat(recovered.state().get("t1").commitTS()).isEqualTo(9);
        recovered.close();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedLogRounds(int rounds) throws Exception {
        ServiceFixture fixture = service();
        for (int i = 0; i < rounds; i++) {
            fixture.service.register("t" + i, bytes("a"), i,
                    Map.of("r1", List.of(mut("a", "v", false)))).join();
        }
        fixture.service.close();
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(fixture.log, command ->
                        CompletableFuture.completedFuture(1L));
        assertThat(recovered.state().size()).isEqualTo(rounds);
        recovered.close();
    }

    // ---------- harness ----------

    private ServiceFixture service() throws Exception {
        Path log = dir.resolve("meta-" + System.nanoTime() + ".log");
        TransactionMetadataService service =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        log);
        return new ServiceFixture(service, log);
    }

    private RouterFixture routerFixture() throws Exception {
        MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
        MvccStorageEngine r2 = new MvccStorageEngine(MemTable.create());
        LockTable l1 = new LockTable();
        LockTable l2 = new LockTable();
        LocalTxnTransport t1 = new LocalTxnTransport(
                new TransactionParticipant("r1", r1, l1, 60_000));
        LocalTxnTransport t2 = new LocalTxnTransport(
                new TransactionParticipant("r2", r2, l2, 60_000));
        TimestampOracle oracle = new TimestampOracle();
        Path metaLog = dir.resolve("meta-" + System.nanoTime() + ".log");
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        metaLog);
        TransactionMetricsRegistry metrics =
                new TransactionMetricsRegistry();
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", t1),
                key -> key.key().length > 0 && key.key()[0] == 'a');
        RegionTxnClient c2 = new RegionTxnClient("r2",
                new TxnParticipantClient("n2", "r2", t2),
                key -> key.key().length > 0 && key.key()[0] == 'b');
        List<RegionTxnClient> clients = List.of(c1, c2);
        DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                key -> key.key().length > 0 && key.key()[0] == 'b' ? c2 : c1,
                clients, metadata, metrics);
        return new RouterFixture(r1, r2, l1, l2, oracle, metadata, metaLog,
                metrics, router, clients);
    }

    private static TxnMessages.Mutation mut(String key, String value,
                                            boolean deleted) {
        return new TxnMessages.Mutation(bytes(key),
                value == null ? null : bytes(value), deleted);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record ServiceFixture(TransactionMetadataService service,
                                  Path log) implements AutoCloseable {
        TransactionMetadataState state() {
            return service.state();
        }

        @Override
        public void close() throws Exception {
            service.close();
        }
    }

    private record RouterFixture(MvccStorageEngine r1, MvccStorageEngine r2,
                                 LockTable l1, LockTable l2,
                                 TimestampOracle oracle,
                                 TransactionMetadataService metadata,
                                 Path metaLog,
                                 TransactionMetricsRegistry metrics,
                                 DistributedTxnRouter router,
                                 List<RegionTxnClient> regionClients)
            implements AutoCloseable {
        java.util.function.Function<io.tieringkv.mvcc.ByteKey,
                RegionTxnClient> regionOf() {
            return key -> key.key().length > 0 && key.key()[0] == 'b'
                    ? regionClients.get(1) : regionClients.get(0);
        }

        Map<String, List<TxnMessages.Mutation>> mutations(Transaction txn) {
            Map<String, List<TxnMessages.Mutation>> byRegion =
                    new LinkedHashMap<>();
            for (io.tieringkv.mvcc.ByteKey key : txn.writeKeys()) {
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
            ((MemTable) r1.underlying()).close();
            ((MemTable) r2.underlying()).close();
        }
    }
}
