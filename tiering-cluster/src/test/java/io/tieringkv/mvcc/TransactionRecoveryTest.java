package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 事务恢复（ADR-0076）：超时回滚 / primary 已提交补完 / 无永久锁。 */
class TransactionRecoveryTest {

    private final MvccStorageEngine engine =
            new MvccStorageEngine(MemTable.create());
    private final LockTable locks = new LockTable();
    private final TransactionRecoveryManager recovery =
            new TransactionRecoveryManager(engine, 1_000);

    @Test
    void expiredLockRolledBack() {
        long now = System.currentTimeMillis();
        LockRecord lock = new LockRecord(bytes("k"), "txn-old", bytes("k"),
                now - 100_000, 1_000, LockType.WRITE);
        locks.acquire(bytes("k"), lock);
        engine.putVersion(bytes("k"), bytes("provisional"), now - 100_000,
                now - 100_000, WriteType.LOCK);
        TransactionRecoveryManager.RecoveryResult result = recovery.recover(locks, now);
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(locks.size()).isZero();
        assertThat(engine.versions(bytes("k"))).isEmpty();
    }

    @Test
    void freshLockKept() {
        long now = System.currentTimeMillis();
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-live",
                bytes("k"), now, 60_000, LockType.WRITE));
        TransactionRecoveryManager.RecoveryResult result = recovery.recover(locks, now);
        assertThat(result.rolledBack()).isZero();
        assertThat(locks.size()).isEqualTo(1);
    }

    @Test
    void primaryCommittedCompletesLocks() {
        long now = System.currentTimeMillis();
        engine.putVersion(bytes("k"), bytes("committed"), now - 100,
                now - 90, WriteType.PUT);
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-a",
                bytes("k"), now - 100, 60_000, LockType.WRITE));
        locks.acquire(bytes("k2"), new LockRecord(bytes("k2"), "txn-a",
                bytes("k"), now - 100, 60_000, LockType.WRITE));
        TransactionRecoveryManager.RecoveryResult result = recovery.recover(locks, now);
        assertThat(result.committed()).isEqualTo(2);
        assertThat(locks.size()).isZero();
    }

    @Test
    void noPermanentLockAfterRecovery() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            locks.acquire(bytes("k" + i), new LockRecord(bytes("k" + i),
                    "txn-" + i, bytes("k" + i), now - 200_000, 1_000,
                    LockType.WRITE));
        }
        recovery.recover(locks, now);
        assertThat(locks.size()).isZero();
    }

    @Test
    void recoveryIsIdempotent() {
        long now = System.currentTimeMillis();
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-old",
                bytes("k"), now - 100_000, 1_000, LockType.WRITE));
        recovery.recover(locks, now);
        TransactionRecoveryManager.RecoveryResult second =
                recovery.recover(locks, now);
        assertThat(second.rolledBack()).isZero();
    }

    @Test
    void crashDuringPrewriteRecovered() {
        // prewrite 后崩溃：provisional LOCK + 锁残留 → 超时回滚
        long now = System.currentTimeMillis();
        long start = now - 200_000;
        engine.putVersion(bytes("k"), bytes("provisional"), start,
                start, WriteType.LOCK);
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-crash",
                bytes("k"), start, 1_000, LockType.WRITE));
        recovery.recover(locks, now);
        assertThat(locks.size()).isZero();
        assertThat(engine.versions(bytes("k"))).isEmpty();
    }

    @Test
    void crashBeforeCommitRecoveredViaPrimary() {
        // primary 已提交（WriteRecord 存在）→ 清理其余锁
        long now = System.currentTimeMillis();
        long start = now - 100;
        engine.putVersion(bytes("k"), bytes("v"), start, start + 1,
                WriteType.PUT);
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-b",
                bytes("k"), start, 60_000, LockType.WRITE));
        locks.acquire(bytes("k2"), new LockRecord(bytes("k2"), "txn-b",
                bytes("k"), start, 60_000, LockType.WRITE));
        recovery.recover(locks, now);
        assertThat(locks.size()).isZero();
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
    }

    @Test
    void crashAfterCommitNoLockLeft() {
        long now = System.currentTimeMillis();
        long start = now - 100;
        engine.putVersion(bytes("k"), bytes("v"), start, start + 1,
                WriteType.PUT);
        TransactionRecoveryManager.RecoveryResult result = recovery.recover(
                locks, now);
        assertThat(result.committed()).isZero();
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
    }

    @Test
    void leaderRestartRecovery() {
        long now = System.currentTimeMillis();
        locks.acquire(bytes("k"), new LockRecord(bytes("k"), "txn-old",
                bytes("k"), now - 200_000, 1_000, LockType.WRITE));
        // 模拟重启后的新 LockTable 重建
        LockTable rebuilt = new LockTable();
        rebuilt.acquire(bytes("k"), locks.check(bytes("k")));
        TransactionRecoveryManager.RecoveryResult result =
                recovery.recover(rebuilt, now);
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(rebuilt.size()).isZero();
    }

    @Test
    void followerRestartPreservesCommitted() {
        long now = System.currentTimeMillis();
        engine.putVersion(bytes("k"), bytes("committed"), now - 200,
                now - 190, WriteType.PUT);
        MvccStorageEngine rebuilt =
                new MvccStorageEngine(engine.underlying());
        assertThat(rebuilt.latestValue(bytes("k"))).isEqualTo(bytes("committed"));
    }

    @ParameterizedTest(name = "expiredLocks {0}")
    @ValueSource(ints = {1, 5, 10, 20})
    void parameterizedExpiredLocks(int count) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            locks.acquire(bytes("k" + i), new LockRecord(bytes("k" + i),
                    "txn-" + i, bytes("k" + i), now - 200_000, 1_000,
                    LockType.WRITE));
        }
        TransactionRecoveryManager.RecoveryResult result =
                recovery.recover(locks, now);
        assertThat(result.rolledBack()).isEqualTo(count);
        assertThat(locks.size()).isZero();
    }

    @ParameterizedTest(name = "mixedLocks {0}")
    @ValueSource(ints = {2, 4, 6})
    void parameterizedMixedRecovery(int freshCount) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < freshCount; i++) {
            locks.acquire(bytes("fresh" + i), new LockRecord(bytes("fresh" + i),
                    "f" + i, bytes("fresh" + i), now, 60_000, LockType.WRITE));
        }
        for (int i = 0; i < freshCount; i++) {
            locks.acquire(bytes("old" + i), new LockRecord(bytes("old" + i),
                    "o" + i, bytes("old" + i), now - 200_000, 1_000,
                    LockType.WRITE));
        }
        TransactionRecoveryManager.RecoveryResult result =
                recovery.recover(locks, now);
        assertThat(result.rolledBack()).isEqualTo(freshCount);
        assertThat(locks.size()).isEqualTo(freshCount);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
