package io.tieringkv.transaction.tso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TSO（ADR-0216）：批量分配 + 单调 + 恢复不回退。 */
class TsoServiceTest {

    @Test
    void singleAllocate() {
        TsoService tso = new TsoService();
        assertThat(tso.allocate()).isZero();
        assertThat(tso.allocate()).isEqualTo(1);
    }

    @Test
    void batchAllocate() {
        TsoService tso = new TsoService();
        long[] first = tso.allocate(10);
        long[] second = tso.allocate(5);
        assertThat(first[0]).isZero();
        assertThat(first[1]).isEqualTo(9);
        assertThat(second[0]).isEqualTo(10);
        assertThat(second[1]).isEqualTo(14);
    }

    @Test
    void watermarkTracksAllocation() {
        TsoService tso = new TsoService();
        tso.allocate(10);
        assertThat(tso.watermark()).isEqualTo(9);
        tso.allocate(1);
        assertThat(tso.watermark()).isEqualTo(10);
    }

    @Test
    void restoreNeverGoesBackwards() {
        TsoService tso = new TsoService();
        tso.allocate(10);
        assertThat(tso.restore(5)).isEqualTo(9);
        assertThat(tso.restore(100)).isEqualTo(100);
        assertThat(tso.restore(50)).isEqualTo(100);
    }

    @Test
    void invalidBatchRejected() {
        assertThatThrownBy(() -> new TsoService().allocate(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "batch {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedBatches(int size) {
        TsoService tso = new TsoService();
        long[] range = tso.allocate(size);
        assertThat(range[1] - range[0]).isEqualTo(size - 1);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRounds(int rounds) {
        TsoService tso = new TsoService();
        for (int i = 0; i < rounds; i++) {
            tso.allocate(10);
        }
        assertThat(tso.watermark()).isEqualTo(rounds * 10L - 1);
    }

    @Test
    void concurrentAllocationMonotonic() throws Exception {
        TsoService tso = new TsoService();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    tso.allocate(10);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(tso.watermark())
                .isEqualTo(4 * 100 * 10L - 1);
    }

    @Test
    void currentTimestampAdvances() {
        TsoService tso = new TsoService();
        tso.allocate(5);
        assertThat(tso.currentTimestamp()).isEqualTo(5);
    }

    @Test
    void restoreAdvancesAllocationCounter() {
        TsoService tso = new TsoService();
        tso.restore(100);
        assertThat(tso.allocate()).isEqualTo(101);
    }

    @Test
    void negativeRestoreRejected() {
        assertThatThrownBy(() -> new TsoService().restore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentBatchAllocationMonotonic() throws Exception {
        TsoService tso = new TsoService();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    tso.allocate(10);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(tso.watermark()).isEqualTo(3999);
    }

    @Test
    void restoreIdempotent() {
        TsoService tso = new TsoService();
        assertThat(tso.restore(50)).isEqualTo(50);
        assertThat(tso.restore(50)).isEqualTo(50);
        assertThat(tso.allocate()).isEqualTo(51);
    }

    @ParameterizedTest(name = "batch={0} rounds={1}")
    @CsvSource({
            "1,1",
            "1,10",
            "2,1",
            "2,10",
            "5,1",
            "5,10",
            "10,1",
            "10,10",
            "20,1",
            "20,10",
            "50,1",
            "50,10",
            "100,1",
            "100,10",
            "1,2",
            "1,5",
            "2,2",
            "2,5",
            "5,2",
            "5,5",
            "10,2",
            "10,5",
            "20,2",
            "20,5",
            "50,2",
            "50,5",
            "100,2",
            "100,5",
            "3,3",
            "4,4",
            "6,6",
            "7,7",
            "8,8",
            "9,9",
            "15,3",
            "25,4",
            "40,5",
            "60,6",
            "80,7",
            "120,8"
    })
    void parameterizedBatchRoundsWatermark(int batch, int rounds) {
        TsoService tso = new TsoService();
        for (int i = 0; i < rounds; i++) {
            tso.allocate(batch);
        }
        assertThat(tso.watermark())
                .isEqualTo(batch * (long) rounds - 1);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10})
    void restoreDoesNotRegressAcrossRounds(int rounds) {
        TsoService tso = new TsoService();
        long restored = 1000L + rounds * 100L;
        tso.restore(restored);
        tso.allocate(10);
        assertThat(tso.watermark()).isGreaterThan(restored);
        assertThat(tso.currentTimestamp())
                .isGreaterThan(tso.watermark());
    }

    @ParameterizedTest(name = "batch={0} restore={1}")
    @CsvSource({
            "1,0,0",
            "10,3,9",
            "10,9,9",
            "10,10,10",
            "10,50,50",
            "100,99,99",
            "100,100,100",
            "100,1000,1000",
            "5,4,4",
            "5,6,6"
    })
    void parameterizedRestoreWatermark(int batch, long restore,
                                       long expected) {
        TsoService tso = new TsoService();
        tso.allocate(batch);
        assertThat(tso.restore(restore)).isEqualTo(expected);
    }
}
