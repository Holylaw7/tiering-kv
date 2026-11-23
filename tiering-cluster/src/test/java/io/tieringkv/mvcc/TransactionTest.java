package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 事务（ADR-0073）：begin/put/get/delete/commit/rollback。 */
class TransactionTest {

    private final TimestampOracle oracle = new TimestampOracle();
    private final MvccStorageEngine engine =
            new MvccStorageEngine(MemTable.create());
    private final LockTable locks = new LockTable();
    private final TransactionManager manager =
            new TransactionManager(oracle, engine, locks, 60_000);

    @Test
    void beginStateActive() {
        assertThat(manager.begin().state()).isEqualTo(Transaction.State.ACTIVE);
    }

    @Test
    void commitSinglePutVisible() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.commit(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
    }

    @Test
    void commitTsGreaterThanStart() {
        Transaction txn = manager.begin();
        long start = txn.startTS();
        txn.put(bytes("k"), bytes("v"));
        manager.commit(txn);
        assertThat(txn.commitTS()).isGreaterThan(start);
    }

    @Test
    void commitTsMonotonicAcrossTxns() {
        Transaction a = manager.begin();
        a.put(bytes("a"), bytes("1"));
        manager.commit(a);
        Transaction b = manager.begin();
        b.put(bytes("b"), bytes("2"));
        manager.commit(b);
        assertThat(b.commitTS()).isGreaterThan(a.commitTS());
    }

    @Test
    void multiKeyAtomic() {
        Transaction txn = manager.begin();
        txn.put(bytes("k1"), bytes("v1"));
        txn.put(bytes("k2"), bytes("v2"));
        txn.put(bytes("k3"), bytes("v3"));
        manager.commit(txn);
        assertThat(engine.latestValue(bytes("k1"))).isEqualTo(bytes("v1"));
        assertThat(engine.latestValue(bytes("k2"))).isEqualTo(bytes("v2"));
        assertThat(engine.latestValue(bytes("k3"))).isEqualTo(bytes("v3"));
    }

    @Test
    void deleteInTxn() {
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        Transaction txn = manager.begin();
        txn.delete(bytes("k"));
        manager.commit(txn);
        assertThat(engine.latestValue(bytes("k"))).isNull();
    }

    @Test
    void getReadsSnapshotNotOwnWrites() {
        engine.putVersion(bytes("k"), bytes("old"), 1, 10, WriteType.PUT);
        Transaction txn = manager.beginAt(100);
        txn.put(bytes("k"), bytes("new"));
        assertThat(txn.get(engine, bytes("k"))).isEqualTo(bytes("old"));
    }

    @Test
    void rollbackCleansLocks() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.rollback(txn);
        assertThat(locks.size()).isZero();
        assertThat(txn.state()).isIn(
                Transaction.State.ROLLED_BACK, Transaction.State.ABORTED);
    }

    @Test
    void rollbackNotVisible() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.rollback(txn);
        assertThat(engine.latestValue(bytes("k"))).isNull();
    }

    @Test
    void commitReachesPreparedThenCommitted() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        txn.commit(engine, locks, oracle, 60_000);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
    }

    @Test
    void commitEmptyTxn() {
        Transaction txn = manager.begin();
        manager.commit(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
    }

    @Test
    void commitTwiceRejected() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.commit(txn);
        assertThatThrownBy(() -> manager.commit(txn))
                .isInstanceOf(TransactionAbortedException.class);
    }

    @Test
    void writeConflictOnSecondTxn() {
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("a"));
        manager.commit(a);
        // T2 在 T1 提交前开始（startTS < T1.commitTS）→ 写写冲突
        Transaction b = manager.beginAt(a.startTS());
        b.put(bytes("k"), bytes("b"));
        assertThatThrownBy(() -> manager.commit(b))
                .isInstanceOf(WriteConflictException.class);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("a"));
    }

    @Test
    void noConflictWhenSecondStartsAfterCommit() {
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("a"));
        manager.commit(a);
        Transaction b = manager.beginAt(a.commitTS() + 5);
        b.put(bytes("k"), bytes("b"));
        manager.commit(b);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("b"));
    }

    @Test
    void committedLockReleasedForSecondTxn() {
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("a"));
        manager.commit(a);
        Transaction b = manager.begin();
        b.put(bytes("k"), bytes("b"));
        manager.commit(b);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("b"));
    }

    @Test
    void primaryKeySetOnCommit() {
        Transaction txn = manager.begin();
        txn.put(bytes("k1"), bytes("v1"));
        txn.put(bytes("k2"), bytes("v2"));
        manager.commit(txn);
        assertThat(txn.primaryKey()).isNotNull();
    }

    @Test
    void snapshotReadInsideTxn() {
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        Transaction txn = manager.beginAt(50);
        assertThat(txn.get(engine, bytes("k"))).isEqualTo(bytes("v1"));
    }

    @Test
    void readSetTracked() {
        Transaction txn = manager.begin();
        txn.get(engine, bytes("read-key"));
        assertThat(txn.readSet()).hasSize(1);
    }

    @Test
    void putOverwriteInTxn() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("a"));
        txn.put(bytes("k"), bytes("b"));
        assertThat(txn.writeCount()).isEqualTo(1);
        manager.commit(txn);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("b"));
    }

    @Test
    void deleteThenPutInTxn() {
        Transaction txn = manager.begin();
        txn.delete(bytes("k"));
        txn.put(bytes("k"), bytes("v"));
        assertThat(txn.writeCount()).isEqualTo(1);
        manager.commit(txn);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
    }

    @Test
    void commitRemovesProvisional() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.commit(txn);
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.versions(bytes("k")).get(0).writeType())
                .isEqualTo(WriteType.PUT);
    }

    @Test
    void rollbackRemovesProvisional() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.rollback(txn);
        assertThat(engine.versions(bytes("k"))).isEmpty();
    }

    @Test
    void txnIdsUnique() {
        assertThat(manager.begin().txnId()).isNotEqualTo(manager.begin().txnId());
    }

    @Test
    void historicalReadAfterCommit() {
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("v1"));
        manager.commit(a);
        Transaction b = manager.begin();
        b.put(bytes("k"), bytes("v2"));
        manager.commit(b);
        assertThat(engine.read(bytes("k"), a.commitTS()).value())
                .isEqualTo(bytes("v1"));
        assertThat(engine.read(bytes("k"), b.commitTS()).value())
                .isEqualTo(bytes("v2"));
    }

    @Test
    void abortedStateOnFailure() {
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.rollback(txn);
        assertThat(txn.state()).isNotEqualTo(Transaction.State.ACTIVE);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 3, 5, 10, 20})
    void parameterizedMultiKeyCommit(int keyCount) {
        Transaction txn = manager.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("p" + i), bytes("v" + i));
        }
        manager.commit(txn);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        for (int i = 0; i < keyCount; i++) {
            assertThat(engine.latestValue(bytes("p" + i)))
                    .isEqualTo(bytes("v" + i));
        }
    }

    @ParameterizedTest(name = "rollbackSize {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void parameterizedRollbackCleans(int keyCount) {
        Transaction txn = manager.begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("r" + i), bytes("v"));
        }
        manager.rollback(txn);
        assertThat(locks.size()).isZero();
        for (int i = 0; i < keyCount; i++) {
            assertThat(engine.latestValue(bytes("r" + i))).isNull();
        }
    }

    @Test
    void writeConflictWhenOverlappingStart() {
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("a"));
        manager.commit(a);
        Transaction b = manager.beginAt(a.startTS());
        b.put(bytes("k"), bytes("b"));
        assertThatThrownBy(() -> manager.commit(b))
                .isInstanceOf(WriteConflictException.class);
    }

    @Test
    void deleteConflictDetected() {
        Transaction a = manager.begin();
        a.put(bytes("k"), bytes("a"));
        manager.commit(a);
        Transaction b = manager.beginAt(a.startTS());
        b.delete(bytes("k"));
        assertThatThrownBy(() -> manager.commit(b))
                .isInstanceOf(WriteConflictException.class);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
