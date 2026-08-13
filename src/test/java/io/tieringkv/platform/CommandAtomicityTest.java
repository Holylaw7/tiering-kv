package io.tieringkv.platform;

import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.memory.BatchWriteRequest;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.Mutation;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 命令并发原子性与竞态（Phase 51 Goal 7）。 */
class CommandAtomicityTest {

    private static byte[] key(int i) {
        return ("incr-key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static int segment(byte[] key) {
        int hash = 0x811c9dc5;
        for (byte b : key) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash & 255;
    }

    @Test
    void concurrentIncrNoLostUpdate() throws Exception {
        MemTable table = MemTable.create();
        runConcurrent(table, 100, 1000);
        assertThat(table.get(key(0))).isEqualTo(
                "100000".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void concurrentIncrThroughWalNoLostUpdate() throws Exception {
        Path dir = Files.createTempDirectory("wal-incr-race");
        WALManager wal = new WALManager(WALConfig.defaults(dir));
        WALStorageEngine engine = new WALStorageEngine(wal,
                MemTable.create());
        runConcurrent(engine, 50, 500);
        assertThat(engine.get(key(0))).isEqualTo(
                "25000".getBytes(StandardCharsets.UTF_8));
        wal.close();
    }

    @Test
    void concurrentAppendProducesFullLength() throws Exception {
        MemTable table = MemTable.create();
        AtomicStringOps atomic = table;
        int threads = 50;
        int ops = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                start.await();
                for (int i = 0; i < ops; i++) {
                    atomic.append(key(1), "x".getBytes(
                            StandardCharsets.UTF_8));
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .isTrue();
        assertThat(table.get(key(1))).hasSize(threads * ops);
    }

    @Test
    void concurrentSetNxExactlyOneWinner() throws Exception {
        MemTable table = MemTable.create();
        AtomicStringOps atomic = table;
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                if (atomic.putIfAbsent(key(2), "v".getBytes(
                        StandardCharsets.UTF_8))) {
                    winners.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .isTrue();
        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    void concurrentGetDelReturnsUniqueOldValues() throws Exception {
        MemTable table = MemTable.create();
        for (int i = 0; i < 100; i++) {
            table.put(("gdel-key-" + i).getBytes(
                            StandardCharsets.UTF_8),
                    ("v" + i).getBytes(
                    StandardCharsets.UTF_8));
        }
        AtomicStringOps atomic = table;
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger nulls = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    byte[] old = atomic.getDelete(
                            ("gdel-key-" + i).getBytes(
                                    StandardCharsets.UTF_8));
                    if (old == null) {
                        nulls.incrementAndGet();
                    } else {
                        seen.add(new String(old,
                                StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .isTrue();
        assertThat(seen).hasSize(100);
        assertThat(nulls.get()).isEqualTo(100 * threads - 100);
    }

    @Test
    void expireAtPastMakesKeyMissing() {
        MemTable table = MemTable.create();
        AtomicStringOps atomic = table;
        table.put(key(4), "v".getBytes(StandardCharsets.UTF_8));
        assertThat(atomic.expireAt(key(4),
                System.currentTimeMillis() - 100)).isTrue();
        assertThat(atomic.ttlMillis(key(4))).isEqualTo(-2);
        assertThat(table.get(key(4))).isNull();
    }

    @Test
    void ttlRaceSmoke() throws Exception {
        MemTable table = MemTable.create();
        AtomicStringOps atomic = table;
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            while (running.get()) {
                table.put(key(4), "v".getBytes(
                        StandardCharsets.UTF_8));
                atomic.expireAt(key(4),
                        System.currentTimeMillis() + 10);
            }
            return null;
        });
        pool.submit(() -> {
            while (running.get()) {
                byte[] value = table.get(key(4));
                if (value != null) {
                    assertThat(value).isEqualTo(
                            "v".getBytes(
                                    StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        Thread.sleep(200);
        running.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .isTrue();
    }

    @ParameterizedTest(name = "threads={0} ops={1}")
    @MethodSource("threadOpMatrix")
    void concurrentIncrMatrix(int threads, int ops)
            throws Exception {
        MemTable table = MemTable.create();
        runConcurrent(table, threads, ops);
        assertThat(table.get(key(0))).isEqualTo(
                Long.toString((long) threads * ops).getBytes(
                        StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "batch keys={0}")
    @MethodSource("batchMatrix")
    void sameSegmentBatchNeverTorn(int keyCount) throws Exception {
        List<byte[]> keys = new ArrayList<>();
        int targetSegment = -1;
        for (int i = 0; i < 100_000 && keys.size() < keyCount; i++) {
            byte[] candidate = ("batch-key-" + i).getBytes(
                    StandardCharsets.UTF_8);
            int seg = segment(candidate);
            if (targetSegment == -1) {
                targetSegment = seg;
                keys.add(candidate);
            } else if (seg == targetSegment) {
                keys.add(candidate);
            }
        }
        assertThat(keys).hasSize(keyCount);
        MemTable table = MemTable.create();
        byte[] oldValue = "old-value".getBytes(
                StandardCharsets.UTF_8);
        byte[] newValue = "new-value".getBytes(
                StandardCharsets.UTF_8);
        List<Mutation> mutations = new ArrayList<>();
        for (byte[] key : keys) {
            mutations.add(Mutation.put(key, oldValue,
                    MemTable.NO_TTL));
        }
        table.applyBatch(new BatchWriteRequest(mutations));
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                while (running.get()) {
                    for (byte[] key : keys) {
                        byte[] value = table.get(key);
                        assertThat(
                                java.util.Arrays.equals(value,
                                        oldValue)
                                        || java.util.Arrays.equals(
                                        value, newValue))
                                .isTrue();
                    }
                }
                return null;
            });
        }
        for (int round = 0; round < 50; round++) {
            List<Mutation> flip = new ArrayList<>();
            for (byte[] key : keys) {
                flip.add(Mutation.put(key,
                        round % 2 == 0 ? newValue : oldValue,
                        MemTable.NO_TTL));
            }
            table.applyBatch(new BatchWriteRequest(flip));
        }
        running.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .isTrue();
    }

    private static void runConcurrent(MemTable table,
                                      int threads, int ops)
            throws Exception {
        runConcurrent((AtomicStringOps) table, threads, ops);
    }

    private static void runConcurrent(AtomicStringOps atomic,
                                      int threads, int ops)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                start.await();
                for (int i = 0; i < ops; i++) {
                    atomic.increment(key(0), 1);
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .isTrue();
    }

    static Stream<Arguments> threadOpMatrix() {
        return Stream.of(
                Arguments.of(10, 100),
                Arguments.of(10, 1000),
                Arguments.of(50, 100),
                Arguments.of(50, 1000),
                Arguments.of(100, 100),
                Arguments.of(100, 500),
                Arguments.of(20, 1000),
                Arguments.of(30, 500),
                Arguments.of(40, 250),
                Arguments.of(25, 400),
                Arguments.of(60, 300),
                Arguments.of(80, 200));
    }

    static Stream<Arguments> batchMatrix() {
        return Stream.of(2, 4, 8, 16, 32).map(Arguments::of);
    }
}
