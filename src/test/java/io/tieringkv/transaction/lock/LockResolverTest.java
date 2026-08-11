package io.tieringkv.transaction.lock;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 分布式锁解析（ADR-0089）：orphan / primary / secondary / 状态缓存。 */
class LockResolverTest {

    @TempDir
    Path dir;

    @Test
    void orphanLockRolledBack() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("orphan", "a1", "va");
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        LockResolver.Result result = resolver.resolve(
                "orphan", bytes("a1"), 1);
        assertThat(result.resolution()).isEqualTo(
                LockResolver.Resolution.ROLLED_BACK);
        assertThat(fixture.locks1.size()).isZero();
        assertThat(fixture.r1.latestValue(bytes("a1"))).isNull();
        fixture.close();
    }

    @Test
    void primaryCommittedResolvesCommit() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "a1", "va");
        fixture.metadata.register("t1", bytes("a1"), 1,
                Map.of("r1", List.of(mut("a1", "va", false)))).join();
        fixture.metadata.prepare("t1", 9).join();
        fixture.metadata.commit("t1", 9).join();
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        LockResolver.Result result = resolver.resolve(
                "t1", bytes("a1"), 1);
        assertThat(result.resolution()).isEqualTo(
                LockResolver.Resolution.COMMITTED);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    @Test
    void primaryPreparedResolvesCommit() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "a1", "va");
        fixture.metadata.register("t1", bytes("a1"), 1,
                Map.of("r1", List.of(mut("a1", "va", false)))).join();
        fixture.metadata.prepare("t1", 9).join();
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        assertThat(resolver.resolve("t1", bytes("a1"), 1).resolution())
                .isEqualTo(LockResolver.Resolution.COMMITTED);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        fixture.close();
    }

    @Test
    void metadataRolledBackResolvesRollback() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "a1", "va");
        fixture.metadata.register("t1", bytes("a1"), 1,
                Map.of("r1", List.of(mut("a1", "va", false)))).join();
        fixture.metadata.rollback("t1").join();
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        assertThat(resolver.resolve("t1", bytes("a1"), 1).resolution())
                .isEqualTo(LockResolver.Resolution.ROLLED_BACK);
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    @Test
    void cacheHitsAvoidReResolve() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("ghost", "a1", "va");
        TxnStatusCache cache = new TxnStatusCache(1000);
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(), cache);
        resolver.resolve("ghost", bytes("a1"), 1);
        assertThat(cache.get("ghost", System.currentTimeMillis()))
                .isEqualTo(TxnStatusCache.Status.ROLLED_BACK);
        assertThat(cache.size()).isEqualTo(1);
        fixture.close();
    }

    @Test
    void cacheExpiresAfterTtl() throws Exception {
        TxnStatusCache cache = new TxnStatusCache(10);
        cache.set("t1", TxnStatusCache.Status.COMMITTED,
                System.currentTimeMillis());
        assertThat(cache.get("t1", System.currentTimeMillis() + 100)).isNull();
    }

    @Test
    void secondaryLockCleanedAfterPrimaryCommit() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "a1", "va");
        fixture.prewrite("t1", "a2", "va2");
        fixture.metadata.register("t1", bytes("a1"), 1,
                Map.of("r1", List.of(mut("a1", "va", false),
                        mut("a2", "va2", false)))).join();
        fixture.metadata.prepare("t1", 9).join();
        fixture.metadata.commit("t1", 9).join();
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        resolver.resolve("t1", bytes("a2"), 1);
        assertThat(fixture.locks1.size()).isZero();
        assertThat(fixture.r1.latestValue(bytes("a2"))).isEqualTo(bytes("va2"));
        fixture.close();
    }

    @Test
    void coordinatorCrashWithoutMetadataRollsBack() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("orphan", "a1", "va");
        LockResolver resolver = new LockResolver(null,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        assertThat(resolver.resolve("orphan", bytes("a1"), 1).resolution())
                .isEqualTo(LockResolver.Resolution.ROLLED_BACK);
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    @Test
    void noLockSkips() throws Exception {
        Fixture fixture = fixture();
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        assertThat(resolver.resolve("ghost", bytes("a1"), 1).resolution())
                .isEqualTo(LockResolver.Resolution.SKIPPED);
        fixture.close();
    }

    @Test
    void networkTimeoutResolveIdempotent() throws Exception {
        Fixture fixture = fixture();
        fixture.prewrite("t1", "a1", "va");
        fixture.metadata.register("t1", bytes("a1"), 1,
                Map.of("r1", List.of(mut("a1", "va", false)))).join();
        fixture.metadata.prepare("t1", 9).join();
        fixture.metadata.commit("t1", 9).join();
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        resolver.resolve("t1", bytes("a1"), 1);
        resolver.resolve("t1", bytes("a1"), 1);
        assertThat(fixture.r1.latestValue(bytes("a1"))).isEqualTo(bytes("va"));
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 3, 5, 10})
    void parameterizedSecondaryCleanup(int keyCount) throws Exception {
        Fixture fixture = fixture();
        List<TxnMessages.Mutation> mutations = new java.util.ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            fixture.prewrite("t1", "k" + i, "v" + i);
            mutations.add(mut("k" + i, "v" + i, false));
        }
        fixture.metadata.register("t1", bytes("k0"), 1,
                Map.of("r1", mutations)).join();
        fixture.metadata.prepare("t1", 9).join();
        fixture.metadata.commit("t1", 9).join();
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        for (int i = 0; i < keyCount; i++) {
            resolver.resolve("t1", bytes("k" + i), 1);
        }
        assertThat(fixture.locks1.size()).isZero();
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.r1.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v" + i));
        }
        fixture.close();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedResolveRounds(int rounds) throws Exception {
        Fixture fixture = fixture();
        for (int i = 0; i < rounds; i++) {
            fixture.prewrite("t" + i, "k" + i, "v" + i);
        }
        LockResolver resolver = new LockResolver(fixture.metadata,
                fixture.regionsById(), fixture.hasLock(),
                new TxnStatusCache(1000));
        for (int i = 0; i < rounds; i++) {
            resolver.resolve("t" + i, bytes("k" + i), 1);
        }
        assertThat(fixture.locks1.size()).isZero();
        fixture.close();
    }

    private Fixture fixture() throws Exception {
        MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
        LockTable l1 = new LockTable();
        LocalTxnTransport t1 = new LocalTxnTransport(
                new TransactionParticipant("r1", r1, l1, 60_000));
        Path metaLog = dir.resolve("meta-" + System.nanoTime() + ".log");
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        metaLog);
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", t1), key -> true);
        return new Fixture(r1, l1, metadata,
                new DistributedTxnRouter(new TimestampOracle(),
                        key -> c1, List.of(c1), metadata,
                        new TransactionMetricsRegistry()),
                List.of(c1));
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
                           TransactionMetadataService metadata,
                           DistributedTxnRouter router,
                           List<RegionTxnClient> regionClients)
            implements AutoCloseable {
        void prewrite(String txnId, String key, String value) {
            io.tieringkv.mvcc.PrewriteExecutor prewrite =
                    new io.tieringkv.mvcc.PrewriteExecutor();
            prewrite.prewrite(r1, locks1, bytes(key), bytes(value), false,
                    txnId, bytes(key), 1, 60_000,
                    System.currentTimeMillis(), java.util.Set.of());
        }

        Map<String, RegionTxnClient> regionsById() {
            return Map.of("r1", regionClients.get(0));
        }

        java.util.function.Function<byte[], Boolean> hasLock() {
            return key -> locks1.check(key) != null;
        }

        @Override
        public void close() throws Exception {
            metadata.close();
            ((MemTable) r1.underlying()).close();
        }
    }
}
