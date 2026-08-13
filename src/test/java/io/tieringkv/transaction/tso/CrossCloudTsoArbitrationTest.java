package io.tieringkv.transaction.tso;

import io.tieringkv.transaction.tso.CrossCloudTsoArbitration
        .CloudTimeSource;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration
        .RollbackEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云授时仲裁 + 防时钟回拨（ADR-0237）。 */
class CrossCloudTsoArbitrationTest {

    @Test
    void majorityMedianArbitration() {
        CrossCloudTsoArbitration clock = clock(100, 200, 300,
                100, 1000);
        assertThat(clock.arbitrate()).isEqualTo(200);
    }

    @Test
    void skewFiltering() {
        CrossCloudTsoArbitration clock = new
                CrossCloudTsoArbitration(
                List.of(source("aws", 100),
                        source("gcp", 200),
                        source("azure", 10_000)),
                100, 1000);
        assertThat(clock.arbitrate()).isEqualTo(150);
    }

    @Test
    void timestampMonotonic() {
        CrossCloudTsoArbitration clock = clock(100, 200, 300,
                100, 1000);
        long first = clock.timestamp();
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(first);
    }

    @Test
    void rollbackBeyondWindowFreezes() {
        CrossCloudTsoArbitration clock = rollbackClock(
                1000, 100, 50);
        long last = clock.timestamp();
        assertThat(clock.frozen()).isTrue();
        assertThat(last).isEqualTo(1001);
        assertThat(clock.rollbackEvents()).hasSize(1);
    }

    @Test
    void rollbackWithinWindowTolerated() {
        CrossCloudTsoArbitration clock = rollbackClock(
                1000, 950, 50);
        long ts = clock.timestamp();
        assertThat(clock.frozen()).isFalse();
        assertThat(ts).isEqualTo(1001);
    }

    @Test
    void restoreAdvancesBeyondWatermark() {
        CrossCloudTsoArbitration clock = clock(0, 0, 0,
                100, 1000);
        clock.restore(100);
        assertThat(clock.timestamp()).isEqualTo(101);
    }

    @Test
    void negativeRestoreRejected() {
        assertThatThrownBy(() -> clock(0, 0, 0, 100, 1000)
                .restore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConstructorRejected() {
        assertThatThrownBy(() -> new CrossCloudTsoArbitration(
                null, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrossCloudTsoArbitration(
                List.of(source("aws", 1)), -1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unfreezeResumes() {
        CrossCloudTsoArbitration clock = rollbackClock(
                1000, 100, 50);
        clock.timestamp();
        assertThat(clock.frozen()).isTrue();
        clock.unfreeze();
        assertThat(clock.frozen()).isFalse();
    }

    @Test
    void rollbackEventsRecorded() {
        CrossCloudTsoArbitration clock = rollbackClock(
                1000, 100, 50);
        clock.timestamp();
        List<RollbackEvent> events = clock.rollbackEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).cloud()).isEqualTo("arbitrated");
        assertThat(events.get(0).observedMillis())
                .isEqualTo(100);
    }

    @Test
    void concurrentTimestampStable() throws Exception {
        CrossCloudTsoArbitration clock = clock(0, 0, 0,
                100, 1000);
        java.util.concurrent.ConcurrentLinkedQueue<Long> values =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    values.add(clock.timestamp());
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        java.util.List<Long> all =
                new java.util.ArrayList<>(values);
        assertThat(all).hasSize(4000);
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    void sourcesExposedImmutably() {
        CrossCloudTsoArbitration clock = clock(1, 2, 3,
                100, 1000);
        assertThat(clock.sources()).hasSize(3);
        assertThatThrownBy(() -> clock.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
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
    void parameterizedArbitrationMatrix(long t1, long t2,
                                        long t3, long maxSkew,
                                        long expected) {
        CrossCloudTsoArbitration clock = new
                CrossCloudTsoArbitration(
                List.of(source("aws", t1),
                        source("gcp", t2),
                        source("azure", t3)),
                maxSkew, 1000);
        assertThat(clock.arbitrate()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "high={0} low={1} window={2}")
    @CsvSource({
            "1000,100,50,true",
            "1000,900,50,true",
            "1000,950,50,false",
            "1000,949,50,true",
            "500,100,50,true",
            "500,400,50,true",
            "500,450,50,false",
            "500,449,50,true",
            "2000,1000,100,true",
            "2000,1900,100,false",
            "2000,1899,100,true",
            "3000,2000,100,true",
            "3000,2900,100,false",
            "3000,2899,100,true",
            "10000,5000,200,true",
            "10000,9800,200,false",
            "10000,9799,200,true",
            "800,100,10,true",
            "800,790,10,false",
            "800,789,10,true"
    })
    void parameterizedRollbackMatrix(long high, long low,
                                     long window,
                                     boolean expectedFrozen) {
        CrossCloudTsoArbitration clock = rollbackClock(
                high, low, window);
        clock.timestamp();
        assertThat(clock.frozen()).isEqualTo(expectedFrozen);
    }

    @ParameterizedTest(name = "skew {0}")
    @ValueSource(longs = {0, 1, 5, 10, 25, 50, 75, 100, 150,
            200, 300, 500, 1000})
    void parameterizedSkewValues(long skew) {
        CrossCloudTsoArbitration clock = new
                CrossCloudTsoArbitration(
                List.of(source("aws", 1000),
                        source("gcp", 1100),
                        source("azure", 1200)),
                skew, 1000);
        assertThat(clock.arbitrate())
                .isBetween(1000L, 1200L);
    }

    private static CrossCloudTsoArbitration rollbackClock(
            long high, long low, long maxRollback) {
        CrossCloudTsoArbitration clock =
                new CrossCloudTsoArbitration(List.of(), 1000,
                        maxRollback);
        clock.addSource(source("aws", high));
        clock.addSource(source("gcp", high));
        clock.addSource(source("azure", high));
        clock.timestamp();
        clock.clearSources();
        clock.addSource(source("aws", low));
        clock.addSource(source("gcp", low));
        clock.addSource(source("azure", low));
        return clock;
    }

    private static CrossCloudTsoArbitration clock(
            long t1, long t2, long t3, long maxSkew,
            long maxRollback) {
        return new CrossCloudTsoArbitration(
                List.of(source("aws", t1),
                        source("gcp", t2),
                        source("azure", t3)),
                maxSkew, maxRollback);
    }

    private static CloudTimeSource source(String cloud,
                                          long millis) {
        return new CloudTimeSource(cloud, millis);
    }
}
