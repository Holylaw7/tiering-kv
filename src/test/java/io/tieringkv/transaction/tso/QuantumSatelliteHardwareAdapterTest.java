package io.tieringkv.transaction.tso;

import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter
        .HardwareClock;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter
        .SimulatedHardwareClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 量子/卫星授时硬件适配（ADR-0251）：接口 + 模拟 + 降级。 */
class QuantumSatelliteHardwareAdapterTest {

    @Test
    void simulatedClockReads() {
        SimulatedHardwareClock clock =
                new SimulatedHardwareClock(1000, 10);
        assertThat(clock.readTimeMillis()).isEqualTo(1010);
        assertThat(clock.healthy()).isTrue();
    }

    @Test
    void timestampCorrects() {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(1000, 10, 100);
        assertThat(adapter.timestamp()).isEqualTo(1110);
    }

    @Test
    void timestampMonotonic() {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(1000, 0, 0);
        long first = adapter.timestamp();
        assertThat(adapter.timestamp()).isGreaterThan(first);
    }

    @Test
    void hardwareFailureDegrades() {
        SimulatedHardwareClock clock =
                new SimulatedHardwareClock(1000, 0);
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(clock, 0);
        long first = adapter.timestamp();
        clock.fail();
        assertThat(adapter.healthy()).isFalse();
        assertThat(adapter.timestamp()).isEqualTo(first);
        assertThat(adapter.failures()).isEqualTo(1);
    }

    @Test
    void recoverRestores() {
        SimulatedHardwareClock clock =
                new SimulatedHardwareClock(1000, 0);
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(clock, 0);
        adapter.timestamp();
        clock.fail();
        adapter.timestamp();
        clock.recover();
        assertThat(adapter.healthy()).isTrue();
        assertThat(adapter.timestamp())
                .isGreaterThan(1000);
    }

    @Test
    void failuresCounted() {
        SimulatedHardwareClock clock =
                new SimulatedHardwareClock(1000, 0);
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(clock, 0);
        clock.fail();
        adapter.timestamp();
        adapter.timestamp();
        assertThat(adapter.failures()).isEqualTo(2);
    }

    @Test
    void readingsCounted() {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(1000, 0, 0);
        adapter.timestamp();
        adapter.timestamp();
        assertThat(adapter.readings()).isEqualTo(2);
    }

    @Test
    void restoreAdvances() {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(100, 0, 0);
        adapter.timestamp();
        adapter.restore(1000);
        assertThat(adapter.timestamp())
                .isGreaterThanOrEqualTo(1001);
    }

    @Test
    void restoreRejectsNegative() {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(0, 0, 0);
        assertThatThrownBy(() -> adapter.restore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConstructorRejected() {
        assertThatThrownBy(() ->
                new QuantumSatelliteHardwareAdapter(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new QuantumSatelliteHardwareAdapter(
                        new SimulatedHardwareClock(0, 0), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentTimestampStable() throws Exception {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(0, 0, 0);
        java.util.concurrent.ConcurrentLinkedQueue<Long> values =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    values.add(adapter.timestamp());
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
    void healthyState() {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(1000, 5, 10);
        assertThat(adapter.healthy()).isTrue();
        assertThat(adapter.timestamp()).isEqualTo(1015);
    }

    @ParameterizedTest(name = "base={0} drift={1} delay={2}")
    @CsvSource({
            "0,0,0,0",
            "100,0,0,100",
            "100,10,0,110",
            "1000,100,0,1100",
            "0,50,0,50",
            "1,1,0,2",
            "2,1,0,3",
            "5,5,0,10",
            "10,5,0,15",
            "25,25,0,50",
            "50,25,0,75",
            "100,25,0,125",
            "200,50,0,250",
            "500,50,0,550",
            "1000,250,0,1250",
            "2500,500,0,3000",
            "5000,1000,0,6000",
            "7500,2500,0,10000",
            "10000,5000,0,15000",
            "42,58,0,100",
            "58,42,0,100",
            "7,3,0,10",
            "3,7,0,10",
            "100,0,10,110",
            "100,10,10,120",
            "1234,100,10,1344",
            "4321,200,20,4541",
            "111,222,0,333",
            "333,111,0,444",
            "888,112,0,1000",
            "999,1,0,1000",
            "100,100,0,200",
            "200,100,0,300",
            "300,100,0,400",
            "400,100,0,500"
    })
    void parameterizedCorrection(long base, long drift,
                                 long delay, long expected) {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(base, drift, delay);
        assertThat(adapter.timestamp()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "base={0} drift={1} delay={2}")
    @CsvSource({
            "100,0,0,100,101",
            "100,10,0,110,111",
            "100,0,10,110,111",
            "50,0,0,50,51",
            "1000,0,0,1000,1001",
            "1000,100,0,1100,1101",
            "500,50,10,560,561",
            "200,0,100,300,301",
            "10,5,0,15,16",
            "10,0,5,15,16",
            "25,25,0,50,51",
            "25,0,25,50,51",
            "60,10,0,70,71",
            "60,0,10,70,71",
            "120,0,0,120,121",
            "120,20,0,140,141",
            "30,0,0,30,31",
            "30,5,5,40,41",
            "80,0,20,100,101",
            "80,10,10,100,101"
    })
    void parameterizedMonotonic(long base, long drift,
                                long delay,
                                long expectedFirst,
                                long expectedSecond) {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(base, drift, delay);
        assertThat(adapter.timestamp())
                .isEqualTo(expectedFirst);
        assertThat(adapter.timestamp())
                .isEqualTo(expectedSecond);
    }

    @ParameterizedTest(name = "drift {0}")
    @ValueSource(longs = {0, 1, 5, 10, 25, 50, 100, 250,
            500, 1000, 2000, 5000, 10000})
    void parameterizedDrifts(long drift) {
        QuantumSatelliteHardwareAdapter adapter =
                adapter(1000, drift, 0);
        long ts = adapter.timestamp();
        assertThat(ts).isEqualTo(1000 + drift);
        assertThat(adapter.timestamp()).isGreaterThan(ts);
    }

    private static QuantumSatelliteHardwareAdapter adapter(
            long base, long drift, long delay) {
        return new QuantumSatelliteHardwareAdapter(
                new SimulatedHardwareClock(base, drift), delay);
    }
}
