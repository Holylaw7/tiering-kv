package io.tieringkv.transaction.tso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
}
