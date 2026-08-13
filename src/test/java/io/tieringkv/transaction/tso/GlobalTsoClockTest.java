package io.tieringkv.transaction.tso;

import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSource;
import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全球统一时钟（ADR-0230）：混合授时 + 校准 + 单调 + 恢复不回退。 */
class GlobalTsoClockTest {

    @Test
    void medianCalibration() {
        GlobalTsoClock clock = clock(100, 200, 300);
        assertThat(clock.now()).isEqualTo(200);
    }

    @Test
    void skewFiltering() {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(source(100), source(200),
                        source(10_000)), 100);
        assertThat(clock.now()).isEqualTo(150);
    }

    @Test
    void timestampMonotonic() {
        GlobalTsoClock clock = clock(100, 200, 300);
        long first = clock.timestamp();
        long second = clock.timestamp();
        long third = clock.timestamp();
        assertThat(second).isGreaterThanOrEqualTo(first);
        assertThat(third).isGreaterThanOrEqualTo(second);
    }

    @Test
    void timestampNeverGoesBackwards() {
        GlobalTsoClock clock = clock(1000, 1000, 1000);
        long first = clock.timestamp();
        GlobalTsoClock regressed = clock(10, 10, 10);
        assertThat(regressed.timestamp())
                .isGreaterThanOrEqualTo(10);
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(first);
    }

    @Test
    void restoreAdvancesBeyondWatermark() {
        GlobalTsoClock clock = clock(0, 0, 0);
        clock.restore(100);
        assertThat(clock.timestamp()).isEqualTo(101);
    }

    @Test
    void restoreNeverRegresses() {
        GlobalTsoClock clock = clock(1000, 1000, 1000);
        clock.timestamp();
        clock.restore(500);
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(1001);
    }

    @Test
    void negativeRestoreRejected() {
        assertThatThrownBy(() -> clock(0, 0, 0).restore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConstructorRejected() {
        assertThatThrownBy(() -> new GlobalTsoClock(
                List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GlobalTsoClock(
                List.of(source(1)), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourcesExposedImmutably() {
        GlobalTsoClock clock = clock(1, 2, 3);
        assertThat(clock.sources()).hasSize(3);
        assertThatThrownBy(() -> clock.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void singleSourceNow() {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(source(42)), 10);
        assertThat(clock.now()).isEqualTo(42);
    }

    @Test
    void twoSourceAverage() {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(source(100), source(200)), 100);
        assertThat(clock.now()).isEqualTo(150);
    }

    @Test
    void concurrentTimestampMonotonic() throws Exception {
        GlobalTsoClock clock = clock(0, 0, 0);
        java.util.concurrent.ConcurrentLinkedQueue<Long> timestamps =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    timestamps.add(clock.timestamp());
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        java.util.List<Long> all =
                new java.util.ArrayList<>(timestamps);
        assertThat(all).hasSize(4000);
        assertThat(all).doesNotHaveDuplicates();
        assertThat(java.util.Collections.max(all))
                .isEqualTo(3999);
    }

    @ParameterizedTest(name = "sources {0},{1},{2} skew={3}")
    @CsvSource({
            "100,200,300,50,200",
            "100,200,300,100,200",
            "100,200,300,150,200",
            "0,100,200,50,100",
            "50,100,150,40,100",
            "1,2,3,1,2",
            "1,2,3,0,2",
            "10,20,30,9,20",
            "10,20,30,10,20",
            "5,15,25,10,15",
            "5,15,25,9,15",
            "0,0,0,0,0",
            "1000,1000,1000,0,1000",
            "1,1,100,50,1",
            "1,1,100,99,1",
            "200,300,400,100,300",
            "200,300,400,99,300",
            "7,8,9,1,8",
            "7,8,9,0,8",
            "11,22,33,10,22",
            "11,22,33,11,22",
            "60,70,80,9,70",
            "60,70,80,10,70",
            "90,91,92,1,91",
            "90,91,92,0,91",
            "12,34,56,21,34",
            "12,34,56,22,34",
            "101,202,303,100,202",
            "101,202,303,101,202",
            "3,6,9,2,6",
            "3,6,9,3,6",
            "4,8,12,3,8",
            "4,8,12,4,8",
            "15,30,45,14,30",
            "15,30,45,15,30"
    })
    void parameterizedMedianCalibration(long t1, long t2, long t3,
                                        long maxSkew,
                                        long expected) {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(source(t1), source(t2), source(t3)),
                maxSkew);
        assertThat(clock.now()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "sources {0},{1},{2} min={3}")
    @CsvSource({
            "100,200,300,200",
            "0,0,0,0",
            "1000,2000,3000,2000",
            "10,10,10,10",
            "5,15,25,15",
            "1,2,3,2",
            "7,8,9,8",
            "12,34,56,34",
            "101,202,303,202",
            "60,70,80,70",
            "90,91,92,91",
            "3,6,9,6",
            "4,8,12,8",
            "15,30,45,30",
            "11,22,33,22",
            "20,40,60,40",
            "25,50,75,50",
            "30,60,90,60",
            "35,70,105,70",
            "40,80,120,80"
    })
    void parameterizedTimestampMinimum(long t1, long t2, long t3,
                                       long minExpected) {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(source(t1), source(t2), source(t3)), 100);
        long first = clock.timestamp();
        assertThat(first).isGreaterThanOrEqualTo(minExpected);
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(first);
    }

    @ParameterizedTest(name = "skew {0}")
    @ValueSource(longs = {0, 1, 5, 10, 25, 50, 75, 100, 150,
            200, 300, 500, 1000})
    void parameterizedSkewValues(long skew) {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(source(1000), source(1100),
                        source(1200)), skew);
        long now = clock.now();
        assertThat(now).isBetween(1000L, 1200L);
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(now);
    }

    private static GlobalTsoClock clock(long t1, long t2,
                                        long t3) {
        return new GlobalTsoClock(
                List.of(source(t1), source(t2), source(t3)),
                100);
    }

    private static TimeSource source(long millis) {
        return new TimeSource(TimeSourceType.SIMULATED, millis);
    }
}
