package io.tieringkv.sql.txn;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.geo.GeoDecision;
import io.tieringkv.transaction.geo.GeoDecisionLog;
import io.tieringkv.transaction.geo.GeoRegionTxnClient;
import io.tieringkv.transaction.geo.GeoRpcTransport;
import io.tieringkv.transaction.geo.GeoTransactionCoordinator;
import io.tieringkv.transaction.geo.LocalGeoRpcTransport;
import io.tieringkv.transaction.participant.TransactionParticipant;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL 写 2PC 真实协调器端到端（ADR-0144）：生命周期 + 决策 + 恢复。 */
class SqlTxnCoordinatorAdapterTest {

    @TempDir
    Path dir;

    @Test
    void beginRequiresWritePermission() throws Exception {
        CredentialManager credentials = new CredentialManager();
        SqlTxnCoordinatorAdapter adapter = adapter(credentials);
        assertThatThrownBy(() -> adapter.begin(
                credentials.issue(Role.READER, 60_000)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void beginCommitRoundTripPersists() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        assertThat(adapter.commit()).isTrue();
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(bytes("va1"));
        assertThat(adapter.inTransaction()).isFalse();
    }

    @Test
    void crossRegionCommitPersistsBoth() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        adapter.write(bytes("b1"), bytes("vb1"), false);
        assertThat(adapter.commit()).isTrue();
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(bytes("va1"));
        assertThat(fixture.engine("r2").latestValue(bytes("b1")))
                .isEqualTo(bytes("vb1"));
    }

    @Test
    void rollbackDiscardsAndLogsDecision() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        assertThat(adapter.rollback()).isTrue();
        assertThat(adapter.pendingCount()).isZero();
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isNull();
    }

    @Test
    void decisionLogContainsCommit() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        adapter.commit();
        List<GeoDecision> decisions = fixture.decisionLog().readAll();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).decision())
                .isEqualTo(GeoDecision.Decision.COMMIT);
    }

    @Test
    void prewriteFailureReturnsFalseAndCleansSession() throws Exception {
        Fixture fixture = fixture();
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
        Map<String, GeoRegionTxnClient> clients =
                new LinkedHashMap<>();
        clients.put("r1", new GeoRegionTxnClient("r1", failing));
        clients.put("r2", new GeoRegionTxnClient("r2",
                fixture.transport()));
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(fixture.decisionLog(),
                        clients, key -> key[0] == 'b');
        CredentialManager credentials = fixture.credentials();
        SqlTxnCoordinatorAdapter adapter =
                new SqlTxnCoordinatorAdapter(coordinator, credentials);
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        assertThat(adapter.commit()).isFalse();
        assertThat(adapter.inTransaction()).isFalse();
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isNull();
    }

    @Test
    void recoverCountsCommitDecisions() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        adapter.commit();
        adapter.begin(fixture.token());
        adapter.write(bytes("a2"), bytes("va2"), false);
        adapter.rollback();
        assertThat(adapter.recover()).isEqualTo(1);
    }

    @Test
    void deleteMutationPersistsAsTombstone() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        adapter.commit();
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), null, true);
        adapter.commit();
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isNull();
    }

    @Test
    void writeWithoutBeginRejected() throws Exception {
        CredentialManager credentials = new CredentialManager();
        SqlTxnCoordinatorAdapter adapter = adapter(credentials);
        assertThatThrownBy(() -> adapter.write(
                bytes("k"), bytes("v"), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doubleBeginRejected() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        assertThatThrownBy(() -> adapter.begin(fixture.token()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitWithoutBeginRejected() throws Exception {
        CredentialManager credentials = new CredentialManager();
        SqlTxnCoordinatorAdapter adapter = adapter(credentials);
        assertThatThrownBy(adapter::commit)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollbackWithoutBeginRejected() throws Exception {
        CredentialManager credentials = new CredentialManager();
        SqlTxnCoordinatorAdapter adapter = adapter(credentials);
        assertThatThrownBy(adapter::rollback)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokedTokenCommitRejected() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        fixture.credentials().revoke(fixture.token());
        assertThatThrownBy(adapter::commit)
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void emptyTxnCommitsWithoutDecision() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        assertThat(adapter.commit()).isTrue();
        assertThat(fixture.decisionLog().readAll()).isEmpty();
    }

    @Test
    void sequentialTransactions() throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        for (int i = 0; i < 5; i++) {
            adapter.begin(fixture.token());
            adapter.write(bytes("a" + i), bytes("v" + i), false);
            assertThat(adapter.commit()).isTrue();
        }
        assertThat(fixture.engine("r1").latestValue(bytes("a4")))
                .isEqualTo(bytes("v4"));
    }

    @Test
    void threeRegionCommit() throws Exception {
        Fixture fixture = fixture(3);
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        adapter.write(bytes("b1"), bytes("vb1"), false);
        assertThat(adapter.commit()).isTrue();
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(bytes("va1"));
        assertThat(fixture.engine("r2").latestValue(bytes("b1")))
                .isEqualTo(bytes("vb1"));
    }

    @Test
    void adapterRecoveryAfterReopen() throws Exception {
        Path logDir = dir.resolve("recover");
        CredentialManager credentials = new CredentialManager();
        Fixture first = fixture(logDir, 2, credentials);
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                first.coordinator(), credentials);
        adapter.begin(first.token());
        adapter.write(bytes("a1"), bytes("va1"), false);
        adapter.commit();

        Fixture reopened = fixture(logDir, 2, credentials);
        SqlTxnCoordinatorAdapter recovered =
                new SqlTxnCoordinatorAdapter(reopened.coordinator(),
                        credentials);
        assertThat(recovered.recover()).isEqualTo(1);
        assertThat(reopened.decisionLog().readAll())
                .extracting(GeoDecision::txnId)
                .isNotEmpty();
    }

    @ParameterizedTest(name = "write volume {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedWriteVolume(int count) throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        for (int i = 0; i < count; i++) {
            adapter.write(bytes("a" + i), bytes("v" + i), false);
        }
        assertThat(adapter.commit()).isTrue();
        assertThat(fixture.engine("r1").latestValue(
                bytes("a" + (count - 1))))
                .isEqualTo(bytes("v" + (count - 1)));
    }

    @ParameterizedTest(name = "value size {0}")
    @ValueSource(ints = {64, 4096, 65536})
    void parameterizedValueSizes(int size) throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        byte[] value = new byte[size];
        adapter.begin(fixture.token());
        adapter.write(bytes("a1"), value, false);
        assertThat(adapter.commit()).isTrue();
        assertThat(fixture.engine("r1").latestValue(bytes("a1")))
                .isEqualTo(value);
    }

    @ParameterizedTest(name = "cross region writes {0}")
    @ValueSource(ints = {2, 8, 32})
    void parameterizedCrossRegionCounts(int count) throws Exception {
        Fixture fixture = fixture();
        SqlTxnCoordinatorAdapter adapter = new SqlTxnCoordinatorAdapter(
                fixture.coordinator(), fixture.credentials());
        adapter.begin(fixture.token());
        for (int i = 0; i < count; i++) {
            adapter.write(bytes(i % 2 == 0 ? "a" + i : "b" + i),
                    bytes("v" + i), false);
        }
        assertThat(adapter.commit()).isTrue();
        assertThat(fixture.engine("r1").latestValue(bytes("a0")))
                .isEqualTo(bytes("v0"));
        if (count >= 2) {
            assertThat(fixture.engine("r2").latestValue(bytes("b1")))
                    .isEqualTo(bytes("v1"));
        }
    }

    @Test
    void concurrentAdapterCommits() throws Exception {
        Fixture fixture = fixture();
        int writers = 4;
        int perWriter = 10;
        List<Thread> threads = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            Thread thread = new Thread(() -> {
                SqlTxnCoordinatorAdapter adapter =
                        new SqlTxnCoordinatorAdapter(
                                fixture.coordinator(),
                                fixture.credentials());
                try {
                    for (int i = 0; i < perWriter; i++) {
                        String key = "a" + (writer * perWriter + i);
                        adapter.begin(fixture.token());
                        adapter.write(bytes(key), bytes("v"), false);
                        if (!adapter.commit()) {
                            failures.incrementAndGet();
                        }
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
    void clientRetriesTransientFailure() throws Exception {
        Fixture fixture = fixture();
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
                bytes("a"), List.of(new TxnMessages.Mutation(
                        bytes("a"), bytes("v"), false)))).join()
                .succeeded()).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void clientAlreadySucceededIdempotent() {
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
                bytes("a"), List.of(new TxnMessages.Mutation(
                        bytes("a"), bytes("v"), false)))).join()
                .succeeded()).isTrue();
    }

    private SqlTxnCoordinatorAdapter adapter(
            CredentialManager credentials) throws Exception {
        Map<String, GeoRegionTxnClient> clients = Map.of(
                "r1", new GeoRegionTxnClient("r1",
                        new LocalGeoRpcTransport()));
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(
                        GeoDecisionLog.open(dir.resolve(
                                "adapter-log-" + System.nanoTime())),
                        clients, key -> false);
        return new SqlTxnCoordinatorAdapter(coordinator, credentials);
    }

    private Fixture fixture() throws Exception {
        CredentialManager credentials = new CredentialManager();
        return fixture(dir.resolve("log-" + System.nanoTime()), 2,
                credentials);
    }

    private Fixture fixture(int regionCount) throws Exception {
        CredentialManager credentials = new CredentialManager();
        return fixture(dir.resolve("log-" + System.nanoTime()),
                regionCount, credentials);
    }

    private static Fixture fixture(Path logDir, int regionCount,
                                   CredentialManager credentials)
            throws Exception {
        GeoDecisionLog decisionLog = GeoDecisionLog.open(logDir);
        LocalGeoRpcTransport transport = new LocalGeoRpcTransport();
        Map<String, GeoRegionTxnClient> clients = new LinkedHashMap<>();
        Map<String, MvccStorageEngine> engines = new LinkedHashMap<>();
        for (int i = 1; i <= regionCount; i++) {
            String region = "r" + i;
            MvccStorageEngine engine = new MvccStorageEngine(
                    MemTable.create());
            engines.put(region, engine);
            transport.register(region, new TransactionParticipant(
                    region, engine, new LockTable(), 60_000));
            clients.put(region, new GeoRegionTxnClient(region,
                    transport));
        }
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(decisionLog, clients,
                        key -> key.length > 0 && key[0] == 'b');
        return new Fixture(decisionLog, transport, coordinator,
                engines, credentials,
                credentials.issue(Role.WRITER, 60_000));
    }

    private record Fixture(GeoDecisionLog decisionLog,
                           LocalGeoRpcTransport transport,
                           GeoTransactionCoordinator coordinator,
                           Map<String, MvccStorageEngine> engines,
                           CredentialManager credentials,
                           String token) {
        MvccStorageEngine engine(String region) {
            return engines.get(region);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
