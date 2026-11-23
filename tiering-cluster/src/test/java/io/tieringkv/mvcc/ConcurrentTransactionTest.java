package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发事务（Phase 19）：100 写者 + 100 读者；无脏读/无永久锁。 */
class ConcurrentTransactionTest {

    @Test
    void hundredWritersHundredReaders() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TimestampOracle oracle = new TimestampOracle();
        TransactionManager manager = new TransactionManager(oracle, engine,
                locks, 60_000);
        int writers = 100;
        int readers = 100;
        int perWriter = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + readers);
        AtomicInteger commitFailures = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            final int id = w;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        Transaction txn = manager.begin();
                        txn.put(bytes("wk:" + id), bytes("v" + id + ":" + i));
                        try {
                            manager.commit(txn);
                        } catch (RuntimeException e) {
                            commitFailures.incrementAndGet();
                            manager.rollback(txn);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        AtomicInteger dirtyReads = new AtomicInteger();
        for (int r = 0; r < readers; r++) {
            final int id = r;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        Transaction txn = manager.beginAt(1);
                        byte[] value = txn.get(engine, bytes("wk:" + (id % writers)));
                        if (value != null) {
                            String text = new String(value, StandardCharsets.UTF_8);
                            if (!text.matches("v\\d+:\\d+")) {
                                dirtyReads.incrementAndGet();
                            }
                        }
                        manager.rollback(txn);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(dirtyReads.get()).isZero();
        assertThat(locks.size()).isZero();
        // 写者自己的键不应冲突（不同键），提交数应等于全部尝试
        assertThat(commitFailures.get()).isZero();
        for (int w = 0; w < writers; w++) {
            assertThat(engine.latestValue(bytes("wk:" + w))).isNotNull();
        }
    }

    @Test
    void hotKeyConflictsNoPermanentLock() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TimestampOracle oracle = new TimestampOracle();
        TransactionManager manager = new TransactionManager(oracle, engine,
                locks, 60_000);
        int writers = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        Transaction txn = manager.begin();
                        txn.put(bytes("hot"), bytes("x"));
                        try {
                            manager.commit(txn);
                            committed.incrementAndGet();
                        } catch (RuntimeException e) {
                            conflicts.incrementAndGet();
                            manager.rollback(txn);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(committed.get()).isGreaterThan(0);
        assertThat(conflicts.get()).isGreaterThan(0);
        assertThat(locks.size()).isZero();
    }

    @Test
    void concurrentReadersSnapshotIsolation() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, new LockTable(), 60_000);
        Transaction readTxn = manager.beginAt(50);
        byte[] at50 = readTxn.get(engine, bytes("k"));
        Transaction writer = manager.begin();
        writer.put(bytes("k"), bytes("v2"));
        manager.commit(writer);
        assertThat(readTxn.get(engine, bytes("k"))).isEqualTo(at50);
    }

    @Test
    void noDirtyWriteBetweenTxns() throws Exception {
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
        } catch (WriteConflictException ignored) {
            manager.rollback(b);
        }
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("a"));
    }

    @Test
    void noPhantomVersions() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, new LockTable(), 60_000);
        for (int i = 0; i < 5; i++) {
            Transaction txn = manager.begin();
            txn.put(bytes("k"), bytes("v" + i));
            manager.commit(txn);
        }
        assertThat(engine.versions(bytes("k"))).hasSize(5);
    }

    @Test
    void noDuplicateCommit() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, locks, 60_000);
        Transaction txn = manager.begin();
        txn.put(bytes("k"), bytes("v"));
        manager.commit(txn);
        long commitTS = txn.commitTS();
        try {
            manager.commit(txn);
        } catch (TransactionAbortedException ignored) {
        }
        assertThat(engine.versions(bytes("k"))).hasSize(1);
        assertThat(engine.versions(bytes("k")).get(0).commitTS())
                .isEqualTo(commitTS);
    }

    @Test
    void concurrentDifferentKeysNoConflict() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, locks, 60_000);
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    Transaction txn = manager.begin();
                    txn.put(bytes("key-" + id), bytes("v" + id));
                    manager.commit(txn);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        for (int t = 0; t < threads; t++) {
            assertThat(engine.latestValue(bytes("key-" + t)))
                    .isEqualTo(bytes("v" + t));
        }
        assertThat(locks.size()).isZero();
    }

    @Test
    void readersDuringWritersNoDirtyRead() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, new LockTable(), 60_000);
        Transaction writer = manager.begin();
        writer.put(bytes("k"), bytes("committed"));
        manager.commit(writer);
        Transaction reader = manager.begin();
        byte[] before = reader.get(engine, bytes("k"));
        Transaction updater = manager.begin();
        updater.put(bytes("k"), bytes("new"));
        // 未提交：读者快照不受影响
        assertThat(reader.get(engine, bytes("k"))).isEqualTo(before);
        manager.rollback(updater);
    }

    @Test
    void manyTransactionsNoLeak() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionManager manager = new TransactionManager(
                new TimestampOracle(), engine, locks, 60_000);
        for (int i = 0; i < 1000; i++) {
            Transaction txn = manager.begin();
            txn.put(bytes("k" + i), bytes("v"));
            manager.commit(txn);
        }
        assertThat(locks.size()).isZero();
        assertThat(engine.versionCount()).isEqualTo(1000);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
