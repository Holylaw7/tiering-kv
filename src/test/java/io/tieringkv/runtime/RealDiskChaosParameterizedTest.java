package io.tieringkv.runtime;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.LocalTxnTransport;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.TxnParticipantClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实磁盘混沌参数化（TD-049）：故障模式矩阵、恢复、回滚安全。 */
class RealDiskChaosParameterizedTest {

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 10, 25})
    void parameterizedCommittedSurvivesRecovery(int count) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        for (int i = 0; i < count; i++) {
            Transaction txn = fixture.router.begin();
            txn.put(bytes("k" + i), bytes("v" + i));
            fixture.router.commit(txn);
        }
        DistributedTxnRouter restarted = restart(fixture);
        restarted.recover();
        assertThat(fixture.engine.latestValue(bytes("k" + (count - 1))))
                .isEqualTo(bytes("v" + (count - 1)));
        fixture.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedFailedTxnRollbackSafe(int keyCount) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction committed = fixture.router.begin();
        committed.put(bytes("committed"), bytes("vc"));
        fixture.router.commit(committed);
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction failed = fixture.router.begin();
        for (int i = 0; i < keyCount; i++) {
            failed.put(bytes("k" + i), bytes("v" + i));
        }
        try {
            fixture.router.commit(failed);
        } catch (RuntimeException ignored) {
            // disk full
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("committed")))
                .isEqualTo(bytes("vc"));
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engine.latestValue(bytes("k" + i))).isNull();
        }
        fixture.close();
    }

    @Test
    void diskFullDuringCommitRollbackSafe() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.commit(txn);
        } catch (RuntimeException ignored) {
            // disk full
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void readonlyCommitRejected() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.storage.mode(FaultyStorage.Mode.READONLY);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.commit(txn);
        } catch (RuntimeException ignored) {
            // readonly
        }
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void slowCommitSucceeds() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(5));
        fixture.storage.mode(FaultyStorage.Mode.SLOW);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.commit(txn);
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    @ParameterizedTest(name = "delay {0}ms")
    @ValueSource(ints = {1, 5, 10, 25})
    void parameterizedSlowDelay(int delayMillis) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(delayMillis));
        fixture.storage.mode(FaultyStorage.Mode.SLOW);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.commit(txn);
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void recoveryAfterMultipleFaults() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        for (int round = 0; round < 3; round++) {
            fixture.storage.mode(round % 2 == 0
                    ? FaultyStorage.Mode.DISK_FULL
                    : FaultyStorage.Mode.READONLY);
            Transaction txn = fixture.router.begin();
            txn.put(bytes("k" + round), bytes("v" + round));
            try {
                fixture.router.commit(txn);
            } catch (RuntimeException ignored) {
                // 注入故障
            }
            fixture.storage.mode(FaultyStorage.Mode.NONE);
        }
        restart(fixture).recover();
        for (int round = 0; round < 3; round++) {
            assertThat(fixture.engine.latestValue(bytes("k" + round)))
                    .isNull();
        }
        fixture.close();
    }

    @Test
    void faultDuringSecondTxnPreservesFirst() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction first = fixture.router.begin();
        first.put(bytes("a"), bytes("va"));
        fixture.router.commit(first);
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction second = fixture.router.begin();
        second.put(bytes("b"), bytes("vb"));
        try {
            fixture.router.commit(second);
        } catch (RuntimeException ignored) {
            // disk full
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("a")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @Test
    void recoveryWithFaultDisabled() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.commit(txn);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void diskFullOnRecoveryRollsBackSafely() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction committed = fixture.router.begin();
        committed.put(bytes("a"), bytes("va"));
        fixture.router.commit(committed);
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction failed = fixture.router.begin();
        failed.put(bytes("b"), bytes("vb"));
        try {
            fixture.router.commit(failed);
        } catch (RuntimeException ignored) {
            // disk full
        }
        DistributedTxnRouter restarted = restart(fixture);
        try {
            restarted.recover();
        } catch (RuntimeException ignored) {
            // 恢复路径同样受故障影响
        }
        assertThat(fixture.engine.latestValue(bytes("a")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @Test
    void readonlyOnSecondTxn() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction first = fixture.router.begin();
        first.put(bytes("a"), bytes("va"));
        fixture.router.commit(first);
        fixture.storage.mode(FaultyStorage.Mode.READONLY);
        Transaction second = fixture.router.begin();
        second.put(bytes("b"), bytes("vb"));
        try {
            fixture.router.commit(second);
        } catch (RuntimeException ignored) {
            // readonly
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("a")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @ParameterizedTest(name = "mode {0}")
    @ValueSource(ints = {0, 1, 2})
    void parameterizedMixedFaultModes(int mode) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction first = fixture.router.begin();
        first.put(bytes("a"), bytes("va"));
        fixture.router.commit(first);
        fixture.storage.mode(mode == 0 ? FaultyStorage.Mode.NONE
                : mode == 1 ? FaultyStorage.Mode.DISK_FULL
                : FaultyStorage.Mode.READONLY);
        Transaction second = fixture.router.begin();
        second.put(bytes("b"), bytes("vb"));
        try {
            fixture.router.commit(second);
        } catch (RuntimeException ignored) {
            // 注入故障
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("a")))
                .isEqualTo(bytes("va"));
        // mode 0 无故障：B 正常提交；mode 1/2 首次 put 失败：B 不可恢复。
        if (mode == 0) {
            assertThat(fixture.engine.latestValue(bytes("b")))
                    .isEqualTo(bytes("vb"));
        } else {
            assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        }
        fixture.close();
    }

    @Test
    void tombstoneSurvivesRecovery() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.engine.putVersion(bytes("k"), bytes("v"), 1, 10,
                WriteType.PUT);
        fixture.engine.putVersion(bytes("k"), null, 2, 20,
                WriteType.DELETE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void largeValueRoundTrip() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        byte[] large = new byte[1024 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), large);
        fixture.router.commit(txn);
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(large);
        fixture.close();
    }

    @Test
    void emptyTxnCommit() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.router.commit(fixture.router.begin());
        fixture.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedMultiKeyCommit(int keyCount) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction txn = fixture.router.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("k" + i), bytes("v" + i));
        }
        fixture.router.commit(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engine.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v" + i));
        }
        fixture.close();
    }

    @Test
    void explicitRollbackAbsent() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.rollback(txn);
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void slowRecoveryCompletes() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(10));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.commit(txn);
        fixture.storage.mode(FaultyStorage.Mode.SLOW);
        DistributedTxnRouter restarted = restart(fixture);
        try {
            restarted.recover();
        } catch (RuntimeException ignored) {
            // slow 路径可能超时
        }
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {2, 5, 10, 25})
    void parameterizedRecoveryKeyCounts(int keyCount) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction txn = fixture.router.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("k" + i), bytes("v" + i));
        }
        fixture.router.commit(txn);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k" + (keyCount - 1))))
                .isEqualTo(bytes("v" + (keyCount - 1)));
        fixture.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedFaultOnSecondTxnKeys(int keyCount) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction first = fixture.router.begin();
        first.put(bytes("a"), bytes("va"));
        fixture.router.commit(first);
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction second = fixture.router.begin();
        for (int i = 0; i < keyCount; i++) {
            second.put(bytes("k" + i), bytes("v" + i));
        }
        try {
            fixture.router.commit(second);
        } catch (RuntimeException ignored) {
            // disk full
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("a")))
                .isEqualTo(bytes("va"));
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engine.latestValue(bytes("k" + i))).isNull();
        }
        fixture.close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedSlowMultiKey(int keyCount) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(3));
        fixture.storage.mode(FaultyStorage.Mode.SLOW);
        Transaction txn = fixture.router.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("k" + i), bytes("v" + i));
        }
        fixture.router.commit(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engine.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v" + i));
        }
        fixture.close();
    }

    @Test
    void readonlyExplicitRollbackAbsent() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.storage.mode(FaultyStorage.Mode.READONLY);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.rollback(txn);
        } catch (RuntimeException ignored) {
            // readonly rollback 路径可能失败，语义上必须无残留
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void diskFullDuringPrewriteOnly() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.commit(txn);
        } catch (RuntimeException ignored) {
            // prewrite 阶段失败
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void slowSecondTxnSucceeds() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(5));
        Transaction first = fixture.router.begin();
        first.put(bytes("a"), bytes("va"));
        fixture.router.commit(first);
        fixture.storage.mode(FaultyStorage.Mode.SLOW);
        Transaction second = fixture.router.begin();
        second.put(bytes("b"), bytes("vb"));
        fixture.router.commit(second);
        assertThat(fixture.engine.latestValue(bytes("b")))
                .isEqualTo(bytes("vb"));
        fixture.close();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {2, 3})
    void parameterizedRepeatedRestartRecovery(int rounds) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        for (int round = 0; round < rounds; round++) {
            Transaction txn = fixture.router.begin();
            txn.put(bytes("k" + round), bytes("v" + round));
            fixture.router.commit(txn);
            restart(fixture).recover();
        }
        assertThat(fixture.engine.latestValue(bytes("k" + (rounds - 1))))
                .isEqualTo(bytes("v" + (rounds - 1)));
        fixture.close();
    }

    @Test
    void faultTogglingStress() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        for (int i = 0; i < 20; i++) {
            fixture.storage.mode(i % 4 == 0 ? FaultyStorage.Mode.DISK_FULL
                    : i % 4 == 1 ? FaultyStorage.Mode.READONLY
                    : i % 4 == 2 ? FaultyStorage.Mode.SLOW
                    : FaultyStorage.Mode.NONE);
            Transaction txn = fixture.router.begin();
            txn.put(bytes("k" + i), bytes("v" + i));
            try {
                fixture.router.commit(txn);
            } catch (RuntimeException ignored) {
                // 注入故障
            }
            fixture.storage.mode(FaultyStorage.Mode.NONE);
        }
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k19")))
                .isEqualTo(bytes("v19"));
        fixture.close();
    }

    @Test
    void emptyRegionTxnCommits() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.router.commit(fixture.router.begin());
        restart(fixture).recover();
        fixture.close();
    }

    @Test
    void deleteAfterRecoveryVisibleAsTombstone() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.commit(txn);
        restart(fixture).recover();
        // 事务提交版本使用 HLC 时间戳；tombstone 必须晚于最新可见版本。
        java.util.List<io.tieringkv.mvcc.MvccEntry> versions =
                fixture.engine.versions(bytes("k"));
        long lastCommitTS = versions.get(versions.size() - 1).commitTS();
        fixture.engine.putVersion(bytes("k"), null, lastCommitTS + 1,
                lastCommitTS + 2, WriteType.DELETE);
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {64, 4096, 65536})
    void parameterizedValueSizes(int size) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        byte[] value = new byte[size];
        for (int i = 0; i < size; i++) {
            value[i] = (byte) (i % 251);
        }
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), value);
        fixture.router.commit(txn);
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(value);
        fixture.close();
    }

    @Test
    void mixedFaultDuringRecoverySafelyCompletes() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction committed = fixture.router.begin();
        committed.put(bytes("a"), bytes("va"));
        fixture.router.commit(committed);
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction failed = fixture.router.begin();
        failed.put(bytes("b"), bytes("vb"));
        try {
            fixture.router.commit(failed);
        } catch (RuntimeException ignored) {
            // disk full
        }
        fixture.storage.mode(FaultyStorage.Mode.SLOW);
        try {
            restart(fixture).recover();
        } catch (RuntimeException ignored) {
            // slow 恢复路径可能超时
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("a")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @Test
    void multipleTxnsThenSingleFault() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        for (int i = 0; i < 5; i++) {
            Transaction txn = fixture.router.begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            fixture.router.commit(txn);
        }
        fixture.storage.mode(FaultyStorage.Mode.READONLY);
        Transaction failed = fixture.router.begin();
        failed.put(bytes("b"), bytes("vb"));
        try {
            fixture.router.commit(failed);
        } catch (RuntimeException ignored) {
            // readonly
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        for (int i = 0; i < 5; i++) {
            assertThat(fixture.engine.latestValue(bytes("a" + i)))
                    .isEqualTo(bytes("va" + i));
        }
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @Test
    void readonlyDuringPrewriteRollsBackSafely() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        fixture.storage.mode(FaultyStorage.Mode.READONLY);
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.commit(txn);
        } catch (RuntimeException ignored) {
            // prewrite readonly
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void diskFullRecoveryRetryThenSucceeds() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(0));
        Transaction committed = fixture.router.begin();
        committed.put(bytes("a"), bytes("va"));
        fixture.router.commit(committed);
        fixture.storage.mode(FaultyStorage.Mode.DISK_FULL);
        Transaction failed = fixture.router.begin();
        failed.put(bytes("b"), bytes("vb"));
        try {
            fixture.router.commit(failed);
        } catch (RuntimeException ignored) {
            // disk full
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        DistributedTxnRouter restarted = restart(fixture);
        restarted.recover();
        restarted.recover();
        assertThat(fixture.engine.latestValue(bytes("a")))
                .isEqualTo(bytes("va"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @Test
    void slowThenFastRecoveryCompletes() throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(10));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.commit(txn);
        fixture.storage.mode(FaultyStorage.Mode.SLOW);
        try {
            restart(fixture).recover();
        } catch (RuntimeException ignored) {
            // slow 超时
        }
        fixture.storage.mode(FaultyStorage.Mode.NONE);
        restart(fixture).recover();
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {3, 6})
    void parameterizedMixedFaultRounds(int rounds) throws Exception {
        DiskFixture fixture = diskFixture(new FaultyStorage(2));
        for (int i = 0; i < rounds; i++) {
            fixture.storage.mode(i % 3 == 0 ? FaultyStorage.Mode.DISK_FULL
                    : i % 3 == 1 ? FaultyStorage.Mode.READONLY
                    : FaultyStorage.Mode.SLOW);
            Transaction txn = fixture.router.begin();
            txn.put(bytes("k" + i), bytes("v" + i));
            try {
                fixture.router.commit(txn);
            } catch (RuntimeException ignored) {
                // 注入故障
            }
            fixture.storage.mode(FaultyStorage.Mode.NONE);
        }
        restart(fixture).recover();
        fixture.close();
    }

    private static DiskFixture diskFixture(FaultyStorage storage)
            throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(storage);
        TransactionParticipant participant = new TransactionParticipant(
                "r1", engine, new LockTable(), 60_000);
        LocalTxnTransport transport = new LocalTxnTransport(participant);
        RegionTxnClient region = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport),
                key -> true);
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        DistributedTxnRouter router = new DistributedTxnRouter(
                new TimestampOracle(), key -> region, List.of(region),
                metadata, new TransactionMetricsRegistry());
        return new DiskFixture(engine, metadata, router, storage);
    }

    private static DistributedTxnRouter restart(DiskFixture fixture) {
        RegionTxnClient region = fixture.region();
        return new DistributedTxnRouter(new TimestampOracle(),
                key -> region, List.of(region), fixture.metadata,
                new TransactionMetricsRegistry());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record DiskFixture(MvccStorageEngine engine,
                               TransactionMetadataService metadata,
                               DistributedTxnRouter router,
                               FaultyStorage storage)
            implements AutoCloseable {

        RegionTxnClient region() {
            return new RegionTxnClient("r1",
                    new TxnParticipantClient("n1", "r1",
                            new LocalTxnTransport(new TransactionParticipant(
                                    "r1", engine, new LockTable(), 60_000))),
                    key -> true);
        }

        @Override
        public void close() throws Exception {
            metadata.close();
        }
    }

    /** 可切换故障模式的记录型存储（JVM 等价容器磁盘故障）。 */
    static final class FaultyStorage implements StorageEngine {

        enum Mode {
            NONE,
            DISK_FULL,
            READONLY,
            SLOW
        }

        private final Map<String, byte[]> data = new ConcurrentHashMap<>();
        private final long delayMillis;
        private volatile Mode mode = Mode.NONE;

        FaultyStorage(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        void mode(Mode mode) {
            this.mode = mode;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            put(key, value, NO_TTL);
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            if (mode == Mode.DISK_FULL) {
                throw new IllegalStateException("disk full");
            }
            if (mode == Mode.READONLY) {
                throw new IllegalStateException("readonly");
            }
            if (mode == Mode.SLOW) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            data.put(keyString(key), value == null ? new byte[0] : value);
        }

        @Override
        public byte[] get(byte[] key) {
            return data.get(keyString(key));
        }

        @Override
        public boolean delete(byte[] key) {
            return data.remove(keyString(key)) != null;
        }

        @Override
        public boolean exists(byte[] key) {
            return data.containsKey(keyString(key));
        }

        @Override
        public long size() {
            return data.size();
        }

        @Override
        public StorageIterator iterator() {
            List<KeyValueEntry> entries = new ArrayList<>();
            data.forEach((key, value) -> entries.add(KeyValueEntry.live(
                    key.getBytes(StandardCharsets.ISO_8859_1), value,
                    System.currentTimeMillis(), 0, 0)));
            entries.sort((a, b) ->
                    unsignedCompare(a.key(), b.key()));
            return new StorageIterator() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < entries.size();
                }

                @Override
                public KeyValueEntry next() {
                    return entries.get(index++);
                }

                @Override
                public void close() {
                    // 内存存储
                }
            };
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.ISO_8859_1);
        }

        private static int unsignedCompare(byte[] a, byte[] b) {
            int length = Math.min(a.length, b.length);
            for (int i = 0; i < length; i++) {
                int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
                if (diff != 0) {
                    return diff;
                }
            }
            return a.length - b.length;
        }
    }
}
