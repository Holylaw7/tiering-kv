package io.tieringkv.transaction.tso;

import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource
        .SourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 量子/卫星授时源原型（ADR-0244）：校正 + 单调 + 防回拨。 */
class QuantumSatelliteTimeSourceTest {

    @Test
    void correctedAddsDelay() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.SATELLITE, 100);
        assertThat(source.corrected(1000)).isEqualTo(1100);
    }

    @Test
    void timestampMonotonic() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.QUANTUM, 0);
        long first = source.timestamp(1000);
        long second = source.timestamp(1000);
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void timestampNeverBackwards() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.HYBRID, 0);
        long first = source.timestamp(1000);
        long second = source.timestamp(10);
        assertThat(second).isGreaterThanOrEqualTo(first);
    }

    @Test
    void restoreAdvances() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.SATELLITE, 0);
        source.timestamp(100);
        source.restore(1000);
        assertThat(source.timestamp(0))
                .isGreaterThanOrEqualTo(1001);
    }

    @Test
    void restoreRejectsNegative() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.QUANTUM, 0);
        assertThatThrownBy(() -> source.restore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidKindRejected() {
        assertThatThrownBy(() -> new QuantumSatelliteTimeSource(
                null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidDelayRejected() {
        assertThatThrownBy(() -> new QuantumSatelliteTimeSource(
                SourceKind.QUANTUM, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correctedReadingsCounted() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.HYBRID, 5);
        source.corrected(1);
        source.corrected(2);
        source.corrected(3);
        assertThat(source.correctedReadings()).isEqualTo(3);
    }

    @Test
    void hybridKind() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.HYBRID, 10);
        assertThat(source.kind()).isEqualTo(SourceKind.HYBRID);
        assertThat(source.propagationDelayMillis())
                .isEqualTo(10);
    }

    @Test
    void deterministicCorrection() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.SATELLITE, 42);
        assertThat(source.corrected(58)).isEqualTo(100);
        assertThat(source.corrected(58)).isEqualTo(100);
    }

    @Test
    void concurrentTimestampStable() throws Exception {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.QUANTUM, 0);
        java.util.concurrent.ConcurrentLinkedQueue<Long> values =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    values.add(source.timestamp(0));
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
    void quantumKind() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.QUANTUM, 0);
        assertThat(source.kind()).isEqualTo(SourceKind.QUANTUM);
    }

    @ParameterizedTest(name = "source={0} delay={1}")
    @CsvSource({
            "0,0,0",
            "100,0,100",
            "100,10,110",
            "1000,100,1100",
            "0,50,50",
            "1,1,2",
            "2,1,3",
            "5,5,10",
            "10,5,15",
            "25,25,50",
            "50,25,75",
            "100,25,125",
            "200,50,250",
            "500,50,550",
            "1000,250,1250",
            "2500,500,3000",
            "5000,1000,6000",
            "7500,2500,10000",
            "10000,5000,15000",
            "42,58,100",
            "58,42,100",
            "7,3,10",
            "3,7,10",
            "1000,0,1000",
            "1000,1,1001",
            "1234,100,1334",
            "4321,200,4521",
            "111,222,333",
            "333,111,444",
            "888,112,1000",
            "999,1,1000",
            "100,100,200",
            "200,100,300",
            "300,100,400",
            "400,100,500"
    })
    void parameterizedCorrection(long sourceTime, long delay,
                                 long expected) {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.SATELLITE, delay);
        assertThat(source.corrected(sourceTime))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "delay={0} t1={1} t2={2}")
    @CsvSource({
            "0,100,100,101",
            "0,100,200,200",
            "10,100,100,111",
            "10,100,50,111",
            "0,0,0,1",
            "0,1,0,2",
            "5,10,10,16",
            "5,10,1,16",
            "100,1000,1000,1101",
            "100,1000,500,1101",
            "25,50,50,76",
            "25,50,0,76",
            "50,100,100,151",
            "50,100,25,151",
            "10,5,10,20",
            "10,5,0,16",
            "100,50,100,200",
            "100,50,0,151",
            "20,30,30,51",
            "20,30,5,51"
    })
    void parameterizedMonotonic(long delay, long t1, long t2,
                                long expectedSecond) {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.HYBRID, delay);
        long first = source.timestamp(t1);
        assertThat(first).isEqualTo(t1 + delay);
        assertThat(source.timestamp(t2))
                .isEqualTo(expectedSecond);
    }

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(longs = {0, 1, 5, 10, 25, 50, 100, 250,
            500, 1000, 2000, 5000, 10000})
    void parameterizedDelays(long delay) {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.QUANTUM, delay);
        long ts = source.timestamp(1000);
        assertThat(ts).isEqualTo(1000 + delay);
        assertThat(source.timestamp(1000))
                .isGreaterThan(ts);
    }
}
