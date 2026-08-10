package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 事务混沌（Phase 19）：已提交不丢 / 未提交不虚假成功 / 无永久锁。 */
class TransactionChaosTest {

    @Test
    void committedTxnSurvivesStorageRestart() {
        MemTable table = MemTable.create();
        MvccStorageEngine engine = new MvccStorageEngine(table);
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, new LockTable(), 60_000);
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.commit(txn);
        // “重启”：同一底层存储重建 MVCC 视图
        MvccStorageEngine restarted = new MvccStorageEngine(table);
        assertThat(restarted.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        table.close();
    }

    @Test
    void timeoutRollbackNoPermanentLock() {
        long now = System.currentTimeMillis();
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-t",
                bytes("k"), now - 100_000, 1_000, LockType.WRITE));
        TransactionRecoveryManager recovery =
                new TransactionRecoveryManager(engine, 1_000);
        recovery.recover(locks, now);
        assertThat(locks.size()).isZero();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void failedTxnLeavesNoLock() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, locks, 60_000);
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("a"));
        manager.commit(a);
        Transaction b = manager.beginAt(a.startTS());
        b.put(bytes("k"), bytes("b"));
        try {
            manager.commit(b);
        } catch (RuntimeException ignored) {
            manager.rollback(b);
        }
        assertThat(locks.size()).isZero();
        assertThat(b.state()).isNotEqualTo(Transaction.State.ACTIVE);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void leaderKillEquivalentStorageFailure() {
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(),
                new MvccStorageEngine(new FailingStorage()),
                new LockTable(), 60_000);
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        assertThatThrownBy(() -> manager.commit(txn))
                .isInstanceOf(IllegalStateException.class);
        // 未提交事务不虚假成功
        assertThat(txn.state()).isNotEqualTo(Transaction.State.COMMITTED);
    }

    @Test
    void partitionLikeTwoStoragesNoCrossContamination() {
        MvccStorageEngine engineA = new MvccStorageEngine(MemTable.create());
        MvccStorageEngine engineB = new MvccStorageEngine(MemTable.create());
        TransactionCoordinator coordinator =
                new TransactionCoordinator(new TimestampOracle(), 60_000);
        Transaction txn = new Transaction("txn-p", 1);
        txn.put(bytes("a"), bytes("va"));
        txn.put(bytes("b"), bytes("vb"));
        coordinator.commit(txn, java.util.List.of(
                new TransactionCoordinator.Participant("a", engineA, new LockTable(),
                        key -> new String(key.key(), StandardCharsets.UTF_8)
                                .startsWith("a")),
                new TransactionCoordinator.Participant("b", engineB, new LockTable(),
                        key -> new String(key.key(), StandardCharsets.UTF_8)
                                .startsWith("b"))));
        assertThat(engineA.latestValue(bytes("a"))).isEqualTo(bytes("va"));
        assertThat(engineB.latestValue(bytes("b"))).isEqualTo(bytes("vb"));
        assertThat(engineA.latestValue(bytes("b"))).isNull();
        ((MemTable) engineA.underlying()).close();
        ((MemTable) engineB.underlying()).close();
    }

    @Test
    void snapshotRestorePreservesVersions() {
        MemTable table = MemTable.create();
        MvccStorageEngine engine = new MvccStorageEngine(table);
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 20, WriteType.PUT);
        // 快照恢复 = 底层全量重建
        MvccStorageEngine restored = new MvccStorageEngine(table);
        assertThat(restored.versions(bytes("k"))).hasSize(2);
        assertThat(restored.read(bytes("k"), 15).value()).isEqualTo(bytes("v1"));
        table.close();
    }

    @Test
    void txnDuringLeaderChangeNotFalselyConfirmed() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, locks, 60_000);
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        // 模拟 leader 变更：锁表被恢复流程接管 → 事务提交被拒
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "other",
                bytes("k"), 1, 60_000, LockType.WRITE));
        assertThatThrownBy(() -> manager.commit(txn))
                .isInstanceOf(RuntimeException.class);
        assertThat(txn.state()).isNotEqualTo(Transaction.State.COMMITTED);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void restartReleasesStaleLocks() {
        long now = System.currentTimeMillis();
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "stale",
                bytes("k"), now - 200_000, 1_000, LockType.WRITE));
        TransactionRecoveryManager recovery =
                new TransactionRecoveryManager(engine, 1_000);
        recovery.recover(locks, now);
        assertThat(locks.size()).isZero();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void committedTxnNeverLostAfterRecovery() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, new LockTable(), 60_000);
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("committed"));
        manager.commit(a);
        long commitTS = a.commitTS();
        // 恢复扫描不改变已提交版本
        TransactionRecoveryManager recovery =
                new TransactionRecoveryManager(engine, 1_000);
        recovery.recover(new LockTable(), System.currentTimeMillis());
        assertThat(engine.read(bytes("k"), commitTS).value())
                .isEqualTo(bytes("committed"));
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void abortedTxnNeverVisible() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, locks, 60_000);
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("ghost"));
        manager.rollback(txn);
        assertThat(engine.latestValue(bytes("k"))).isNull();
        assertThat(engine.versions(bytes("k"))).isEmpty();
        ((MemTable) engine.underlying()).close();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 始终失败的存储（模拟节点宕机）。 */
    private static final class FailingStorage
            implements io.tieringkv.storage.StorageEngine {
        @Override
        public void put(byte[] key, byte[] value) {
            throw new IllegalStateException("node down");
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            throw new IllegalStateException("node down");
        }

        @Override
        public byte[] get(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public boolean delete(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public boolean exists(byte[] key) {
            throw new IllegalStateException("node down");
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            return new io.tieringkv.storage.StorageIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public io.tieringkv.storage.memory.KeyValueEntry next() {
                    throw new IllegalStateException("empty");
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public long size() {
            throw new IllegalStateException("node down");
        }
    }
}
