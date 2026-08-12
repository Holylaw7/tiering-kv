package io.tieringkv.security.network;

import io.tieringkv.security.network.LearnedHardener.ThresholdAdjustment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 学习型加固（ADR-0197）：阈值自进化 + 上下界 + 审计。 */
class LearnedHardenerTest {

    @Test
    void highRiskLowersThreshold() {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                5);
        assertThat(hardener.learn(true)).isEqualTo(45);
    }

    @Test
    void lowRiskRaisesThreshold() {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                5);
        assertThat(hardener.learn(false)).isEqualTo(55);
    }

    @Test
    void thresholdClampedToMin() {
        LearnedHardener hardener = new LearnedHardener(12, 10, 90,
                5);
        hardener.learn(true);
        assertThat(hardener.learn(true)).isEqualTo(10);
        assertThat(hardener.learn(true)).isEqualTo(10);
    }

    @Test
    void thresholdClampedToMax() {
        LearnedHardener hardener = new LearnedHardener(88, 10, 90,
                5);
        hardener.learn(false);
        assertThat(hardener.learn(false)).isEqualTo(90);
        assertThat(hardener.learn(false)).isEqualTo(90);
    }

    @Test
    void initialThresholdClamped() {
        LearnedHardener hardener = new LearnedHardener(200, 10, 90,
                5);
        assertThat(hardener.threshold()).isEqualTo(90);
        LearnedHardener low = new LearnedHardener(-5, 10, 90, 5);
        assertThat(low.threshold()).isEqualTo(10);
    }

    @Test
    void auditTracksAdjustments() {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                5);
        hardener.learn(true);
        hardener.learn(false);
        assertThat(hardener.audit()).hasSize(2);
        ThresholdAdjustment first = hardener.audit().get(0);
        assertThat(first.before()).isEqualTo(50);
        assertThat(first.after()).isEqualTo(45);
        assertThat(first.reason()).contains("high risk");
    }

    @Test
    void invalidBoundsRejected() {
        assertThatThrownBy(() -> new LearnedHardener(50, 90, 10,
                5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LearnedHardener(50, -1, 90,
                5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LearnedHardener(50, 10, 90,
                0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "step {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedSteps(int step) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                step);
        assertThat(hardener.learn(true)).isEqualTo(50 - step);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRounds(int rounds) {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                5);
        for (int i = 0; i < rounds; i++) {
            hardener.learn(i % 2 == 0);
        }
        assertThat(hardener.threshold()).isBetween(10, 90);
        assertThat(hardener.audit()).hasSize(rounds);
    }

    @Test
    void concurrentLearningStable() throws Exception {
        LearnedHardener hardener = new LearnedHardener(50, 10, 90,
                1);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    hardener.learn(true);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(hardener.threshold()).isEqualTo(10);
        assertThat(hardener.audit()).hasSize(200);
    }
}
