package io.tieringkv.saas.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 试用转化跟踪（ADR-0155）：转化率矩阵。 */
class TrialConversionTrackerTest {

    @Test
    void startsCountTrials() {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        tracker.startTrial();
        tracker.startTrial();
        assertThat(tracker.trialCount()).isEqualTo(2);
    }

    @Test
    void allConvertedRateOne() {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        tracker.startTrial();
        tracker.markConverted();
        assertThat(tracker.conversionRate()).isEqualTo(1.0);
    }

    @Test
    void allExpiredRateZero() {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        tracker.startTrial();
        tracker.markExpired();
        assertThat(tracker.conversionRate()).isZero();
    }

    @Test
    void mixedConversionRate() {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        tracker.startTrial();
        tracker.markConverted();
        tracker.startTrial();
        tracker.markExpired();
        tracker.startTrial();
        tracker.markExpired();
        assertThat(tracker.conversionRate()).isEqualTo(1.0 / 3);
    }

    @Test
    void emptyTrackerRateZero() {
        assertThat(new TrialConversionTracker().conversionRate())
                .isZero();
    }

    @Test
    void ongoingTrialsNotCountedInRate() {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        tracker.startTrial();
        tracker.startTrial();
        tracker.markConverted();
        assertThat(tracker.conversionRate()).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "converted {0} expired {1}")
    @CsvSource({"1,1", "2,1", "3,0", "0,3"})
    void parameterizedConversion(long converted, long expired) {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        for (long i = 0; i < converted; i++) {
            tracker.startTrial();
            tracker.markConverted();
        }
        for (long i = 0; i < expired; i++) {
            tracker.startTrial();
            tracker.markExpired();
        }
        long ended = converted + expired;
        double expected = ended == 0 ? 0
                : (double) converted / ended;
        assertThat(tracker.conversionRate()).isEqualTo(expected);
    }

    @Test
    void countersIndependent() {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        tracker.startTrial();
        tracker.startTrial();
        tracker.markConverted();
        tracker.markExpired();
        assertThat(tracker.convertedCount()).isEqualTo(1);
        assertThat(tracker.expiredCount()).isEqualTo(1);
        assertThat(tracker.trialCount()).isEqualTo(2);
    }

    @Test
    void concurrentTracking() throws Exception {
        TrialConversionTracker tracker =
                new TrialConversionTracker();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    tracker.startTrial();
                    if (i % 2 == 0) {
                        tracker.markConverted();
                    } else {
                        tracker.markExpired();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(tracker.trialCount()).isEqualTo(800);
        assertThat(tracker.convertedCount()).isEqualTo(400);
        assertThat(tracker.expiredCount()).isEqualTo(400);
    }
}
