package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 冲突检测（ADR-0074）：写写/读写/锁冲突。 */
class ConflictTest {

    private final MvccStorageEngine engine =
            new MvccStorageEngine(MemTable.create());
    private final LockTable locks = new LockTable();
    private final ConflictDetector detector = new ConflictDetector();

    @Test
    void writeWriteConflictDetected() {
        engine.putVersion(bytes("k"), bytes("v"), 1, 100, WriteType.PUT);
        assertThatThrownBy(() -> detector.checkWriteConflict(
                engine, bytes("k"), 50))
                .isInstanceOf(WriteConflictException.class);
    }

    @Test
    void writeWriteNoConflictWhenOlder() {
        engine.putVersion(bytes("k"), bytes("v"), 1, 100, WriteType.PUT);
        detector.checkWriteConflict(engine, bytes("k"), 150);
    }

    @Test
    void lockConflictDetected() {
        LockRecord lock = new LockRecord(bytes("k"), "txn-a", bytes("k"),
                1, 60_000, LockType.WRITE);
        locks.acquire(bytes("k"), lock);
        assertThatThrownBy(() -> detector.checkLockConflict(
                locks, bytes("k"), "txn-b"))
                .isInstanceOf(LockConflictException.class);
    }

    @Test
    void lockConflictSameTxnAllowed() {
        LockRecord lock = new LockRecord(bytes("k"), "txn-a", bytes("k"),
                1, 60_000, LockType.WRITE);
        locks.acquire(bytes("k"), lock);
        detector.checkLockConflict(locks, bytes("k"), "txn-a");
    }

    @Test
    void readWriteConflictDetected() {
        engine.putVersion(bytes("k"), bytes("v"), 1, 100, WriteType.PUT);
        var readSet = ConcurrentHashMap.<ByteKey>newKeySet();
        readSet.add(new ByteKey(bytes("k")));
        assertThatThrownBy(() -> detector.checkReadWriteConflict(
                engine, bytes("k"), 50, readSet))
                .isInstanceOf(WriteConflictException.class);
    }

    @Test
    void readWriteNoConflictIfNotRead() {
        engine.putVersion(bytes("k"), bytes("v"), 1, 100, WriteType.PUT);
        detector.checkReadWriteConflict(engine, bytes("k"), 105,
                ConcurrentHashMap.newKeySet());
    }

    @Test
    void differentKeysNoConflict() {
        engine.putVersion(bytes("a"), bytes("v"), 1, 100, WriteType.PUT);
        detector.checkWriteConflict(engine, bytes("b"), 105);
        detector.checkLockConflict(locks, bytes("b"), "txn");
    }

    @Test
    void lockReleaseAllowsSecondTxn() {
        LockRecord lock = new LockRecord(bytes("k"), "txn-a", bytes("k"),
                1, 60_000, LockType.WRITE);
        locks.acquire(bytes("k"), lock);
        locks.release(bytes("k"), "txn-a");
        detector.checkLockConflict(locks, bytes("k"), "txn-b");
    }

    @Test
    void lockTtlExpiry() {
        LockRecord lock = new LockRecord(bytes("k"), "txn-a", bytes("k"),
                System.currentTimeMillis() - 100_000, 1_000, LockType.WRITE);
        assertThat(lock.expired(System.currentTimeMillis())).isTrue();
    }

    @Test
    void lockResolveForceRemoves() {
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-a",
                bytes("k"), 1, 60_000, LockType.WRITE));
        locks.resolve(bytes("k"));
        assertThat(locks.size()).isZero();
    }

    @Test
    void lockAcquireIdempotentForSameTxn() {
        LockRecord lock = new LockRecord(bytes("k"), "txn-a", bytes("k"),
                1, 60_000, LockType.WRITE);
        assertThat(locks.acquire(bytes("k"), lock)).isTrue();
        assertThat(locks.acquire(bytes("k"), lock)).isTrue();
        assertThat(locks.size()).isEqualTo(1);
    }

    @Test
    void lockConflictRejectsSecond() {
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-a",
                bytes("k"), 1, 60_000, LockType.WRITE));
        assertThat(locks.acquire(bytes("k"), new LockRecord(bytes("k"),
                "txn-b", bytes("k"), 2, 60_000, LockType.WRITE))).isFalse();
    }

    @Test
    void lockCheckReturnsHolder() {
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-a",
                bytes("k"), 1, 60_000, LockType.WRITE));
        assertThat(locks.check(bytes("k")).txnId()).isEqualTo("txn-a");
    }

    @Test
    void deleteConflictDetectedAsWrite() {
        engine.putVersion(bytes("k"), bytes("v"), 1, 100, WriteType.PUT);
        assertThatThrownBy(() -> detector.checkWriteConflict(
                engine, bytes("k"), 50))
                .isInstanceOf(WriteConflictException.class);
    }

    @Test
    void noConflictAfterRecoveryResolve() {
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-a",
                bytes("k"), 1, 60_000, LockType.WRITE));
        locks.resolve(bytes("k"));
        detector.checkLockConflict(locks, bytes("k"), "txn-b");
    }

    @Test
    void concurrentLockAcquireSingleWinner() throws Exception {
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < 8; t++) {
            final int id = t;
            threads[t] = new Thread(() -> {
                LockRecord lock = new LockRecord(bytes("k"), "txn-" + id,
                        bytes("k"), id, 60_000, LockType.WRITE);
                if (locks.acquire(bytes("k"), lock)) {
                    wins.incrementAndGet();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(wins.get()).isEqualTo(1);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
