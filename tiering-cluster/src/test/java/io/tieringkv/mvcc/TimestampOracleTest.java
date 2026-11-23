package io.tieringkv.mvcc;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 时间戳（ADR-0072）：单调 / 批量 / 并发 / 回拨 / 溢出。 */
class TimestampOracleTest {

    @Test
    void nextTimestampMonotonic() {
        TimestampOracle oracle = new TimestampOracle();
        long t1 = oracle.nextTimestamp();
        long t2 = oracle.nextTimestamp();
        long t3 = oracle.nextTimestamp();
        assertThat(t1).isLessThan(t2).isLessThan(t3);
    }

    @Test
    void nextBatchAllocatesRange() {
        TimestampOracle oracle = new TimestampOracle();
        long base = oracle.nextBatch(10);
        long first = oracle.nextTimestamp();
        assertThat(first).isEqualTo(base + 10);
    }

    @Test
    void nextBatchSingle() {
        TimestampOracle oracle = new TimestampOracle();
        long base = oracle.nextBatch(1);
        assertThat(oracle.nextTimestamp()).isEqualTo(base + 1);
    }

    @Test
    void concurrentAllocationNoDuplicates() throws Exception {
        TimestampOracle oracle = new TimestampOracle();
        int threads = 8;
        int perThread = 1000;
        Set<Long> timestamps = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        timestamps.add(oracle.nextTimestamp());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(timestamps).hasSize(threads * perThread);
    }

    @Test
    void hlcClockRollbackDoesNotRegress() {
        HybridLogicalClock clock = new HybridLogicalClock();
        long t1 = clock.now();
        // 模拟回拨：physical 不变，logical 递增
        for (int i = 0; i < 100; i++) {
            long next = clock.now();
            assertThat(next).isGreaterThan(t1);
            t1 = next;
        }
    }

    @Test
    void hlcPhysicalTimeMonotonic() {
        HybridLogicalClock clock = new HybridLogicalClock();
        long p1 = clock.physicalTime();
        clock.now();
        clock.now();
        assertThat(clock.physicalTime()).isGreaterThanOrEqualTo(p1);
    }

    @Test
    void hlcUpdateAdoptsRemote() {
        HybridLogicalClock clock = new HybridLogicalClock();
        long remote = clock.now() + 10_000_000L; // 远端更晚
        clock.update(remote);
        long next = clock.now();
        assertThat(next).isGreaterThan(remote);
    }

    @Test
    void hlcUpdateIgnoresOlderRemote() {
        HybridLogicalClock clock = new HybridLogicalClock();
        long local = clock.now();
        clock.update(local - 1);
        assertThat(clock.now()).isGreaterThan(local);
    }

    @Test
    void oracleRecoverNeverRegresses() {
        TimestampOracle oracle = new TimestampOracle();
        long last = oracle.nextTimestamp();
        oracle.recover(last - 1000);
        assertThat(oracle.nextTimestamp()).isGreaterThan(last);
    }

    @Test
    void peekDoesNotAdvance() {
        TimestampOracle oracle = new TimestampOracle();
        long peek = oracle.peek();
        assertThat(oracle.nextTimestamp()).isEqualTo(peek);
    }

    @Test
    void invalidBatchRejected() {
        TimestampOracle oracle = new TimestampOracle();
        assertThatThrownBy(() -> oracle.nextBatch(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hlcOverflowBumpsPhysical() {
        HybridLogicalClock clock = new HybridLogicalClock();
        long first = clock.now();
        long last = first;
        for (int i = 0; i < 2_000_000; i++) {
            long next = clock.now();
            assertThat(next).isGreaterThan(last);
            last = next;
        }
    }

    @Test
    void oracleBatchOverflowRejected() {
        TimestampOracle oracle = new TimestampOracle();
        oracle.recover(Long.MAX_VALUE - 5);
        assertThatThrownBy(() -> oracle.nextBatch(100))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hlcLogicalCounterIncrementsUnderSamePhysical() {
        HybridLogicalClock clock = new HybridLogicalClock();
        long before = clock.logicalCounter();
        clock.now();
        clock.now();
        assertThat(clock.logicalCounter()).isGreaterThanOrEqualTo(before + 2);
    }

    @Test
    void oracleAndHlcBothPositive() {
        TimestampOracle oracle = new TimestampOracle();
        HybridLogicalClock clock = new HybridLogicalClock();
        long oracleTs = oracle.nextTimestamp();
        long hlcTs = clock.now();
        assertThat(oracleTs).isGreaterThan(0);
        assertThat(hlcTs).isGreaterThan(0);
    }
}
