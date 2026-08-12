package io.tieringkv.saas.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 流失检测（ADR-0155）：流失率矩阵。 */
class ChurnDetectorTest {

    @Test
    void noEventsRateZero() {
        assertThat(new ChurnDetector().churnRate()).isZero();
    }

    @Test
    void allChurnedRateOne() {
        ChurnDetector detector = new ChurnDetector();
        detector.recordChurn();
        detector.recordChurn();
        assertThat(detector.churnRate()).isEqualTo(1.0);
    }

    @Test
    void allRenewedRateZero() {
        ChurnDetector detector = new ChurnDetector();
        detector.recordRenewal();
        detector.recordRenewal();
        assertThat(detector.churnRate()).isZero();
    }

    @Test
    void mixedRate() {
        ChurnDetector detector = new ChurnDetector();
        detector.recordChurn();
        detector.recordRenewal();
        detector.recordRenewal();
        assertThat(detector.churnRate()).isEqualTo(1.0 / 3);
    }

    @Test
    void countersTracked() {
        ChurnDetector detector = new ChurnDetector();
        detector.recordChurn();
        detector.recordRenewal();
        assertThat(detector.churnedCount()).isEqualTo(1);
        assertThat(detector.renewedCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "churn {0} renew {1}")
    @CsvSource({"1,1", "2,2", "3,1", "1,3"})
    void parameterizedChurnRates(long churn, long renew) {
        ChurnDetector detector = new ChurnDetector();
        for (long i = 0; i < churn; i++) {
            detector.recordChurn();
        }
        for (long i = 0; i < renew; i++) {
            detector.recordRenewal();
        }
        assertThat(detector.churnRate())
                .isEqualTo((double) churn / (churn + renew));
    }

    @Test
    void concurrentEvents() throws Exception {
        ChurnDetector detector = new ChurnDetector();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    if (i % 3 == 0) {
                        detector.recordChurn();
                    } else {
                        detector.recordRenewal();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(detector.churnedCount()).isEqualTo(272);
        assertThat(detector.renewedCount()).isEqualTo(528);
    }
}
