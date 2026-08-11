package io.tieringkv.transaction.lock;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.LocalTxnTransport;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.TxnParticipantClient;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LockResolver RPC（ADR-0092）：CHECK / RESOLVE / HEARTBEAT。 */
class LockRpcTest {

    @Test
    void checkStatusUnknown() {
        Fixture fixture = fixture();
        TxnMessages.Response response = fixture.region.checkStatus(
                "ghost", 1).join();
        assertThat(response.status()).isEqualTo(TxnMessages.Status.ERROR);
        assertThat(response.message()).isEqualTo("UNKNOWN");
        fixture.close();
    }

    @Test
    void checkStatusLocked() {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "k", "v");
        TxnMessages.Response response = fixture.region.checkStatus(
                "t1", 1).join();
        assertThat(response.message())
                .isEqualTo(TxnMessages.ParticipantState.LOCKED.name());
        fixture.close();
    }

    @Test
    void checkStatusCommitted() {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "k", "v");
        fixture.region.commit("t1", 1, 2, bytes("k"),
                List.of(mut("k", "v", false))).join();
        TxnMessages.Response response = fixture.region.checkStatus(
                "t1", 1).join();
        assertThat(response.message())
                .isEqualTo(TxnMessages.ParticipantState.COMMITTED.name());
        fixture.close();
    }

    @Test
    void resolveLockCommitsPrepared() {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "k", "v");
        TxnMessages.Response response = fixture.region.resolveLock(
                "t1", 1, 9, bytes("k"),
                List.of(mut("k", "v", false)), false).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    @Test
    void resolveLockRollsBackOrphan() {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "k", "v");
        TxnMessages.Response response = fixture.region.resolveLock(
                "t1", 1, 9, bytes("k"),
                List.of(mut("k", "v", false)), true).join();
        assertThat(response.succeeded()).isTrue();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    @Test
    void resolveLockIdempotent() {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "k", "v");
        fixture.region.resolveLock("t1", 1, 9, bytes("k"),
                List.of(mut("k", "v", false)), false).join();
        fixture.region.resolveLock("t1", 1, 9, bytes("k"),
                List.of(mut("k", "v", false)), false).join();
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    @Test
    void lockResolverClientCrossRegion() {
        Fixture a = fixture();
        Fixture b = fixture();
        a.prewrite("t1", "a-key", "va");
        b.prewrite("t1", "b-key", "vb");
        LockResolverClient client = new LockResolverClient(
                key -> key.key()[0] == 'b' ? b.region : a.region);
        TxnMessages.Response status = client.checkStatus(
                bytes("a-key"), "t1", 1);
        assertThat(status.message())
                .isEqualTo(TxnMessages.ParticipantState.LOCKED.name());
        TxnMessages.Response resolved = client.resolve(bytes("a-key"),
                "t1", 1, 9, bytes("a-key"),
                List.of(mut("a-key", "va", false)), false);
        assertThat(resolved.succeeded()).isTrue();
        assertThat(a.engine.latestValue(bytes("a-key")))
                .isEqualTo(bytes("va"));
        assertThat(a.locks.size()).isZero();
        a.close();
        b.close();
    }

    @Test
    void heartbeatLockOverRpc() {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "k", "v");
        io.tieringkv.mvcc.LockRecord before = fixture.locks.check(bytes("k"));
        fixture.region.heartbeat("t1", 1, 120_000).join();
        assertThat(fixture.locks.check(bytes("k")).ttlMillis())
                .isEqualTo(120_000);
        assertThat(fixture.locks.check(bytes("k")).createdAtMillis())
                .isGreaterThanOrEqualTo(before.createdAtMillis());
        fixture.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 2, 4, 8, 16, 32, 64, 128, 3, 5, 6, 10, 12, 20,
            24, 40})
    void parameterizedResolveManyKeys(int keyCount) {
        Fixture fixture = fixture();
        List<TxnMessages.Mutation> mutations = new java.util.ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            fixture.prewrite("t1", "k" + i, "v" + i);
            mutations.add(mut("k" + i, "v" + i, false));
        }
        TxnMessages.Response response = fixture.region.resolveLock(
                "t1", 1, 9, bytes("k0"), mutations, false).join();
        assertThat(response.succeeded()).isTrue();
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engine.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v" + i));
        }
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 2, 4, 8, 3, 5, 6, 10})
    void parameterizedResolveRounds(int rounds) {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "k", "v");
        for (int i = 0; i < rounds; i++) {
            TxnMessages.Response response = fixture.region.resolveLock(
                    "t1", 1, 9, bytes("k"),
                    List.of(mut("k", "v", false)), false).join();
            assertThat(response.succeeded()).isTrue();
        }
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    private Fixture fixture() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        LocalTxnTransport transport = new LocalTxnTransport(
                new TransactionParticipant("r1", engine, locks, 60_000));
        RegionTxnClient region = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport),
                key -> true);
        return new Fixture(engine, locks, region);
    }

    private static TxnMessages.Mutation mut(String key, String value,
                                            boolean deleted) {
        return new TxnMessages.Mutation(bytes(key),
                value == null ? null : bytes(value), deleted);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(MvccStorageEngine engine, LockTable locks,
                           RegionTxnClient region) implements AutoCloseable {
        void prewrite(String txnId, String key, String value) {
            io.tieringkv.mvcc.Transaction txn =
                    new io.tieringkv.mvcc.Transaction(txnId, 1);
            region.prewrite(txn, List.of(mut(key, value, false))).join();
        }

        @Override
        public void close() {
            ((MemTable) engine.underlying()).close();
        }
    }
}
