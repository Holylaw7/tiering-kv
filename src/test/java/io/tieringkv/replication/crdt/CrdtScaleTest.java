package io.tieringkv.replication.crdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Geo CRDT 规模与时钟校准（ADR-0122）。 */
class CrdtScaleTest {

    @ParameterizedTest(name = "nodes {0} keys {1}")
    @ValueSource(ints = {2, 5})
    void scaleSimulatorRuns(int nodes) {
        CrdtScaleSimulator simulator = new CrdtScaleSimulator(nodes, 100);
        simulator.run(20);
        assertThat(simulator.registerCount()).isEqualTo(nodes * 100);
        assertThat(simulator.converged()).isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void scaleSimulatorRounds(int rounds) {
        CrdtScaleSimulator simulator = new CrdtScaleSimulator(3, 50);
        simulator.run(rounds);
        assertThat(simulator.registerCount()).isEqualTo(150);
    }

    @Test
    void clockCalibratorZeroSamples() {
        assertThat(new HybridClockCalibrator().estimateOffset(
                List.of())).isZero();
    }

    @Test
    void clockCalibratorEstimatesOffset() {
        HybridClockCalibrator calibrator = new HybridClockCalibrator();
        List<HybridClockCalibrator.Sample> samples = List.of(
                new HybridClockCalibrator.Sample(1000, 1100),
                new HybridClockCalibrator.Sample(2000, 2100));
        assertThat(calibrator.estimateOffset(samples)).isEqualTo(100);
    }

    @ParameterizedTest(name = "offset {0}")
    @ValueSource(longs = {-1000, 0, 1000})
    void clockAdjust(long offset) {
        HybridClockCalibrator calibrator = new HybridClockCalibrator();
        assertThat(calibrator.adjust(10_000, offset))
                .isEqualTo(10_000 - offset);
    }

    @Test
    void lwwScaleConvergenceSingleKey() {
        LwwRegister a = new LwwRegister();
        LwwRegister b = new LwwRegister();
        for (int round = 0; round < 100; round++) {
            a.set(round, "n" + (round % 2), ("v" + round).getBytes());
            b.set(round, "n" + (round % 2), ("v" + round).getBytes());
        }
        a.merge(b);
        b.merge(a);
        assertThat(a.value()).isEqualTo(b.value());
        assertThat(a.value()).isEqualTo(("v99").getBytes());
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 100, 1000})
    void gCounterScale(int keys) {
        GCounter a = new GCounter();
        GCounter b = new GCounter();
        for (int i = 0; i < keys; i++) {
            a.increment("n1");
            b.increment("n2");
        }
        a.merge(b);
        assertThat(a.value()).isEqualTo(keys * 2L);
    }

    @Test
    void orSetScaleConverges() {
        OrSet a = new OrSet();
        OrSet b = new OrSet();
        for (int i = 0; i < 500; i++) {
            a.add("k" + i, "t" + i);
            b.add("k" + i, "t" + i);
        }
        for (int i = 0; i < 500; i++) {
            a.remove("k" + i, "t" + i);
        }
        a.merge(b);
        b.merge(a);
        assertThat(a.size()).isZero();
        assertThat(b.size()).isZero();
    }

    @Test
    void gSetScaleUnion() {
        GSet a = new GSet();
        GSet b = new GSet();
        for (int i = 0; i < 1000; i++) {
            a.add("a" + i);
            b.add("b" + i);
        }
        a.merge(b);
        assertThat(a.size()).isEqualTo(2000);
    }

    @ParameterizedTest(name = "samples {0}")
    @ValueSource(ints = {1, 5, 20})
    void clockCalibratorSampleCount(int count) {
        List<HybridClockCalibrator.Sample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            samples.add(new HybridClockCalibrator.Sample(
                    i * 1000, i * 1000 + 500));
        }
        assertThat(new HybridClockCalibrator().estimateOffset(samples))
                .isEqualTo(500);
    }
}
