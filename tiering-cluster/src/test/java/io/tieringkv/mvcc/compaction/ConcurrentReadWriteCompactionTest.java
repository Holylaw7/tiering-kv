package io.tieringkv.mvcc.compaction;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.mvcc.gc.GcConfig;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 在线压缩并发（ADR-0085）：读/写/事务不阻塞，无丢失最新版本。 */
class ConcurrentReadWriteCompactionTest {

    @Test
    void concurrentReadersSeeLatestDuringCompaction() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 30; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        AtomicBoolean inconsistent = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                start.await();
                SnapshotReader snapshot = new SnapshotReader();
                for (int i = 0; i < 5_000; i++) {
                    if (!java.util.Arrays.equals(snapshot.get(
                            engine, bytes("k"), Long.MAX_VALUE),
                            bytes("v30"))) {
                        inconsistent.set(true);
                    }
                }
            } catch (Throwable t) {
                inconsistent.set(true);
            }
        });
        reader.start();
        start.countDown();
        for (int round = 0; round < 20; round++) {
            compactor.compact();
        }
        reader.join(30_000);
        compactor.close();
        assertThat(inconsistent).isFalse();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void concurrentWritersNoLostUpdate() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(Long.MAX_VALUE / 2));
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            int writer = w;
            TimestampOracle oracle = new TimestampOracle();
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 1_000; i++) {
                        Transaction txn = new Transaction(
                                "w" + writer + "-" + i,
                                oracle.nextTimestamp());
                        txn.put(bytes("k" + writer),
                                bytes("v" + writer + "-" + i));
                        txn.commit(engine,
                                new io.tieringkv.mvcc.LockTable(),
                                oracle, 60_000);
                    }
                } catch (Throwable t) {
                    failed.set(true);
                }
            });
            writers.add(thread);
            thread.start();
        }
        for (int round = 0; round < 30; round++) {
            compactor.compact();
        }
        for (Thread thread : writers) {
            thread.join(30_000);
        }
        compactor.close();
        assertThat(failed).isFalse();
        for (int w = 0; w < 4; w++) {
            assertThat(engine.latestValue(bytes("k" + w))).isNotNull();
        }
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void concurrentTransactionsDuringCompaction() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(Long.MAX_VALUE / 2));
        AtomicBoolean failed = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                TimestampOracle oracle = new TimestampOracle();
                for (int i = 0; i < 2_000; i++) {
                    Transaction txn = new Transaction("t" + i,
                            oracle.nextTimestamp());
                    txn.put(bytes("hot"), bytes("v" + i));
                    txn.commit(engine, new io.tieringkv.mvcc.LockTable(),
                            oracle, 60_000);
                }
            } catch (Throwable t) {
                failed.set(true);
                error.set(t);
            }
        });
        writer.start();
        for (int round = 0; round < 20; round++) {
            compactor.compact();
        }
        writer.join(30_000);
        compactor.close();
        assertThat(failed).isFalse();
        assertThat(error.get()).isNull();
        assertThat(engine.latestValue(bytes("hot"))).isNotNull();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void repeatedCompactionConvergesToOneVersionPerKey() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 200; i++) {
            for (int v = 1; v <= 20; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(Long.MAX_VALUE - 1));
        for (int round = 0; round < 5; round++) {
            compactor.compact();
        }
        assertThat(engine.versionCount()).isEqualTo(200);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void parallelCompactorsIdempotent() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 10; v++) {
            engine.putVersion(bytes("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        MvccCompactor a = new MvccCompactor(engine, GcConfig.DEFAULT);
        MvccCompactor b = new MvccCompactor(engine, GcConfig.DEFAULT);
        a.updateSafePoint(new SafePoint(100));
        b.updateSafePoint(new SafePoint(100));
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        Thread ta = new Thread(() -> {
            try {
                start.await();
                a.compact();
            } catch (Throwable t) {
                failed.set(true);
            }
        });
        Thread tb = new Thread(() -> {
            try {
                start.await();
                b.compact();
            } catch (Throwable t) {
                failed.set(true);
            }
        });
        ta.start();
        tb.start();
        start.countDown();
        ta.join(30_000);
        tb.join(30_000);
        a.close();
        b.close();
        assertThat(failed).isFalse();
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v10"));
        ((MemTable) engine.underlying()).close();
    }

    @ParameterizedTest(name = "writers {0}")
    @ValueSource(ints = {1, 2, 4, 8})
    void parameterizedWritersDuringCompaction(int writerCount)
            throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(Long.MAX_VALUE / 2));
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < writerCount; w++) {
            int writer = w;
            TimestampOracle oracle = new TimestampOracle();
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 300; i++) {
                        Transaction txn = new Transaction(
                                "w" + writer + "-" + i,
                                oracle.nextTimestamp());
                        txn.put(bytes("k" + writer),
                                bytes("v" + writer + "-" + i));
                        txn.commit(engine,
                                new io.tieringkv.mvcc.LockTable(),
                                oracle, 60_000);
                    }
                } catch (Throwable t) {
                    failed.set(true);
                }
            });
            writers.add(thread);
            thread.start();
        }
        for (int round = 0; round < 10; round++) {
            compactor.compact();
        }
        for (Thread thread : writers) {
            thread.join(30_000);
        }
        compactor.close();
        assertThat(failed).isFalse();
        for (int w = 0; w < writerCount; w++) {
            assertThat(engine.latestValue(bytes("k" + w))).isNotNull();
        }
        ((MemTable) engine.underlying()).close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {10, 50, 100})
    void parameterizedKeysDuringCompaction(int keyCount) throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < keyCount; i++) {
            for (int v = 1; v <= 10; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        MvccCompactor compactor = new MvccCompactor(engine,
                GcConfig.DEFAULT);
        compactor.updateSafePoint(new SafePoint(100));
        for (int round = 0; round < 5; round++) {
            compactor.compact();
        }
        assertThat(engine.versionCount()).isEqualTo(keyCount);
        compactor.close();
        ((MemTable) engine.underlying()).close();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
