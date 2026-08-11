package io.tieringkv.transaction.geo;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.participant.TransactionParticipant;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 地域分布式事务（ADR-0109）：决策先行、跨地域提交、恢复。 */
class GeoTransactionTest {

    @TempDir
    Path dir;

    @Test
    void decisionLogAppendRead() throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("log"));
        log.append(new GeoDecision("t1", GeoDecision.Decision.COMMIT, 42));
        log.append(new GeoDecision("t2", GeoDecision.Decision.ROLLBACK, 0));
        List<GeoDecision> decisions = log.readAll();
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).commitTS()).isEqualTo(42);
        assertThat(decisions.get(1).decision())
                .isEqualTo(GeoDecision.Decision.ROLLBACK);
    }

    @Test
    void decisionLogCorruptTailTolerated() throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("corrupt"));
        for (int i = 0; i < 5; i++) {
            log.append(new GeoDecision("t" + i,
                    GeoDecision.Decision.COMMIT, i));
        }
        Path file = dir.resolve("corrupt").resolve("geo-decisions.log");
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        java.nio.file.Files.write(file, java.util.Arrays.copyOf(bytes,
                bytes.length - 3));
        assertThat(log.readAll().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void decisionLogReopen() throws Exception {
        Path logDir = dir.resolve("reopen");
        GeoDecisionLog first = GeoDecisionLog.open(logDir);
        first.append(new GeoDecision("t1", GeoDecision.Decision.COMMIT, 1));
        GeoDecisionLog reopened = GeoDecisionLog.open(logDir);
        assertThat(reopened.readAll()).hasSize(1);
    }

    @Test
    void singleRegionCommitPersists() throws Exception {
        Fixture fixture = fixture(2);
        GeoTransactionCoordinator.GeoTransaction txn =
                fixture.coordinator.begin(mutations("a1"));
        fixture.coordinator.commit(txn);
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(bytes("va1"));
        assertThat(fixture.engine("r2").latestValue(bytes("a1"))).isNull();
    }

    @Test
    void crossRegionCommitPersistsBoth() throws Exception {
        Fixture fixture = fixture(2);
        GeoTransactionCoordinator.GeoTransaction txn =
                fixture.coordinator.begin(mutations("a1", "b1"));
        fixture.coordinator.commit(txn);
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(bytes("va1"));
        assertThat(fixture.engine("r2").latestValue(bytes("b1")))
                .isEqualTo(bytes("vb1"));
    }

    @Test
    void rollbackRemovesValue() throws Exception {
        Fixture fixture = fixture(2);
        GeoTransactionCoordinator.GeoTransaction txn =
                fixture.coordinator.begin(mutations("a1"));
        fixture.coordinator.commit(txn);
        GeoTransactionCoordinator.GeoTransaction rollback =
                fixture.coordinator.begin(mutations("a1"));
        fixture.coordinator.rollback(rollback);
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(bytes("va1")); // 已有提交不受影响
    }

    @Test
    void decisionLoggedBeforeCommit() throws Exception {
        Fixture fixture = fixture(2);
        GeoTransactionCoordinator.GeoTransaction txn =
                fixture.coordinator.begin(mutations("a1"));
        fixture.coordinator.commit(txn);
        assertThat(fixture.decisionLog.readAll()).hasSize(1);
        assertThat(fixture.decisionLog.readAll().get(0).decision())
                .isEqualTo(GeoDecision.Decision.COMMIT);
    }

    @Test
    void recoverCountsCommitDecisions() throws Exception {
        Fixture fixture = fixture(2);
        GeoTransactionCoordinator.GeoTransaction txn =
                fixture.coordinator.begin(mutations("a1", "b1"));
        fixture.coordinator.commit(txn);
        fixture.coordinator.rollback(fixture.coordinator.begin(
                mutations("a2")));
        assertThat(fixture.coordinator.recover()).isEqualTo(1);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedKeyCounts(int count) throws Exception {
        Fixture fixture = fixture(2);
        List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            mutations.add(mutation("a" + i, "v" + i));
        }
        fixture.coordinator.commit(
                fixture.coordinator.begin(mutations));
        assertThat(fixture.engine("r1").latestValue(
                bytes("a" + (count - 1))))
                .isEqualTo(bytes("v" + (count - 1)));
    }

    @ParameterizedTest(name = "mutations {0}")
    @ValueSource(ints = {1, 8, 32})
    void parameterizedMutationCounts(int count) throws Exception {
        Fixture fixture = fixture(2);
        List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            mutations.add(mutation(i % 2 == 0 ? "a" + i : "b" + i,
                    "v" + i));
        }
        fixture.coordinator.commit(
                fixture.coordinator.begin(mutations));
        assertThat(fixture.engine("r1").latestValue(bytes("a0")))
                .isEqualTo(bytes("v0"));
        if (count >= 2) {
            assertThat(fixture.engine("r2").latestValue(bytes("b1")))
                    .isEqualTo(bytes("v1"));
        }
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {64, 4096})
    void parameterizedValueSizes(int size) throws Exception {
        Fixture fixture = fixture(2);
        byte[] value = new byte[size];
        List<TxnMessages.Mutation> mutations = List.of(
                new TxnMessages.Mutation(bytes("a1"), value, false));
        fixture.coordinator.commit(
                fixture.coordinator.begin(mutations));
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(value);
    }

    @Test
    void deletedMutationPersists() throws Exception {
        Fixture fixture = fixture(2);
        fixture.coordinator.commit(fixture.coordinator.begin(
                List.of(mutation("a1", "v1"))));
        fixture.coordinator.commit(fixture.coordinator.begin(
                List.of(new TxnMessages.Mutation(bytes("a1"), null,
                        true))));
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isNull();
    }

    @Test
    void prewriteFailureRollsBack() throws Exception {
        Fixture fixture = fixture(2);
        GeoRpcTransport failing = new GeoRpcTransport() {
            @Override
            public CompletableFuture<TxnMessages.Response> prewrite(
                    String region, TxnMessages.Prewrite request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.error("disk full"));
            }

            @Override
            public CompletableFuture<TxnMessages.Response> commit(
                    String region, TxnMessages.Commit request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.ok());
            }

            @Override
            public CompletableFuture<TxnMessages.Response> rollback(
                    String region, TxnMessages.Rollback request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.ok());
            }
        };
        Map<String, GeoRegionTxnClient> clients = new java.util.LinkedHashMap<>();
        clients.put("r1", new GeoRegionTxnClient("r1", failing));
        clients.put("r2", new GeoRegionTxnClient("r2",
                fixture.transport()));
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(fixture.decisionLog(),
                        clients, key -> key[0] == 'b');
        GeoTransactionCoordinator.GeoTransaction txn =
                coordinator.begin(mutations("a1"));
        assertThatThrownBy(() -> coordinator.commit(txn))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isNull();
    }

    @Test
    void unknownRegionFails() throws Exception {
        Fixture fixture = fixture(1);
        GeoTransactionCoordinator.GeoTransaction txn =
                fixture.coordinator.begin(mutations("a1", "b1"));
        // b1 路由到未注册的 r2 → 提交失败
        assertThatThrownBy(() -> fixture.coordinator.commit(txn))
                .isInstanceOf(Exception.class);
    }

    @Test
    void clientRetriesOnTransientFailure() {
        AtomicInteger attempts = new AtomicInteger();
        GeoRpcTransport flaky = new GeoRpcTransport() {
            @Override
            public CompletableFuture<TxnMessages.Response> prewrite(
                    String region, TxnMessages.Prewrite request) {
                if (attempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("transient"));
                }
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.ok());
            }

            @Override
            public CompletableFuture<TxnMessages.Response> commit(
                    String region, TxnMessages.Commit request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.ok());
            }

            @Override
            public CompletableFuture<TxnMessages.Response> rollback(
                    String region, TxnMessages.Rollback request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.ok());
            }
        };
        GeoRegionTxnClient client = new GeoRegionTxnClient("r1", flaky);
        assertThat(client.prewrite(new TxnMessages.Prewrite("t1", 1,
                bytes("a"), List.of(mutation("a", "v")))).join()
                .succeeded()).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void clientAlreadySucceeded() {
        GeoRpcTransport transport = new GeoRpcTransport() {
            @Override
            public CompletableFuture<TxnMessages.Response> prewrite(
                    String region, TxnMessages.Prewrite request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.already());
            }

            @Override
            public CompletableFuture<TxnMessages.Response> commit(
                    String region, TxnMessages.Commit request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.already());
            }

            @Override
            public CompletableFuture<TxnMessages.Response> rollback(
                    String region, TxnMessages.Rollback request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.already());
            }
        };
        GeoRegionTxnClient client = new GeoRegionTxnClient("r1", transport);
        assertThat(client.prewrite(new TxnMessages.Prewrite("t1", 1,
                bytes("a"), List.of(mutation("a", "v")))).join()
                .succeeded()).isTrue();
    }

    @Test
    void concurrentGeoCommits() throws Exception {
        Fixture fixture = fixture(2);
        int writers = 4;
        int perWriter = 10;
        List<Thread> threads = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < perWriter; i++) {
                        String key = "a" + (writer * perWriter + i);
                        fixture.coordinator.commit(
                                fixture.coordinator.begin(
                                        List.of(mutation(key, "v"))));
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(15_000);
        }
        assertThat(failures.get()).isZero();
        assertThat(fixture.engine("r1").latestValue(
                bytes("a" + (writers * perWriter - 1))))
                .isEqualTo(bytes("v"));
    }

    @Test
    void emptyTxnCommits() throws Exception {
        Fixture fixture = fixture(2);
        fixture.coordinator.commit(
                fixture.coordinator.begin(List.of()));
        assertThat(fixture.decisionLog.readAll()).isEmpty();
    }

    @Test
    void threeRegionCommit() throws Exception {
        Fixture fixture = fixture(3);
        GeoTransactionCoordinator.GeoTransaction txn =
                fixture.coordinator.begin(mutations("a1", "b1"));
        fixture.coordinator.commit(txn);
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(bytes("va1"));
        assertThat(fixture.engine("r2").latestValue(bytes("b1")))
                .isEqualTo(bytes("vb1"));
    }

    private static Fixture fixture(int regionCount) throws Exception {
        GeoDecisionLog decisionLog = GeoDecisionLog.open(
                java.nio.file.Files.createTempDirectory("geo-log"));
        LocalGeoRpcTransport transport = new LocalGeoRpcTransport();
        Map<String, GeoRegionTxnClient> clients = new java.util.LinkedHashMap<>();
        Map<String, MvccStorageEngine> engines = new java.util.LinkedHashMap<>();
        Map<String, TransactionParticipant> participants =
                new java.util.LinkedHashMap<>();
        for (int i = 1; i <= regionCount; i++) {
            String region = "r" + i;
            MvccStorageEngine engine = new MvccStorageEngine(
                    MemTable.create());
            engines.put(region, engine);
            TransactionParticipant participant =
                    new TransactionParticipant(region, engine,
                            new LockTable(), 60_000);
            participants.put(region, participant);
            transport.register(region, participant);
            clients.put(region, new GeoRegionTxnClient(region, transport));
        }
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(decisionLog, clients,
                        key -> key.length > 0 && key[0] == 'b');
        return new Fixture(decisionLog, transport, coordinator, engines,
                participants);
    }

    private record Fixture(GeoDecisionLog decisionLog,
                           LocalGeoRpcTransport transport,
                           GeoTransactionCoordinator coordinator,
                           Map<String, MvccStorageEngine> engines,
                           Map<String, TransactionParticipant> participants) {
        MvccStorageEngine engine(String region) {
            return engines.get(region);
        }
    }

    private static List<TxnMessages.Mutation> mutations(String... keys) {
        List<TxnMessages.Mutation> list = new ArrayList<>();
        for (String key : keys) {
            list.add(mutation(key, "v" + key));
        }
        return list;
    }

    private static TxnMessages.Mutation mutation(String key, String value) {
        return new TxnMessages.Mutation(bytes(key), bytes(value), false);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
