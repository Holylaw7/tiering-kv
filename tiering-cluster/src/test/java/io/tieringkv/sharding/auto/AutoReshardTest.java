package io.tieringkv.sharding.auto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 负载驱动自动重分片（ADR-0132）：判定、冷却、熔断。 */
class AutoReshardTest {

    private static ReshardPolicy policy() {
        return new ReshardPolicy(1000, 100, 50, 3);
    }

    @Test
    void highQpsSplits() {
        AutoReshardController controller =
                new AutoReshardController(policy());
        assertThat(controller.decide(new LoadProbe(2000, 10, 100)))
                .isEqualTo(AutoReshardController.Decision.SPLIT);
    }

    @Test
    void lowQpsMerges() {
        AutoReshardController controller =
                new AutoReshardController(policy());
        assertThat(controller.decide(new LoadProbe(50, 10, 100)))
                .isEqualTo(AutoReshardController.Decision.MERGE);
    }

    @Test
    void normalQpsNoop() {
        AutoReshardController controller =
                new AutoReshardController(policy());
        assertThat(controller.decide(new LoadProbe(500, 10, 100)))
                .isEqualTo(AutoReshardController.Decision.NOOP);
    }

    @ParameterizedTest(name = "qps {0}")
    @ValueSource(longs = {0, 50, 500, 1000, 5000})
    void parameterizedQpsDecisions(long qps) {
        AutoReshardController controller =
                new AutoReshardController(policy());
        AutoReshardController.Decision decision =
                controller.decide(new LoadProbe(qps, 5, 100));
        if (qps > 1000) {
            assertThat(decision)
                    .isEqualTo(AutoReshardController.Decision.SPLIT);
        } else if (qps < 100) {
            assertThat(decision)
                    .isEqualTo(AutoReshardController.Decision.MERGE);
        } else {
            assertThat(decision)
                    .isEqualTo(AutoReshardController.Decision.NOOP);
        }
    }

    @Test
    void cooldownSuppressesRapidActions() throws Exception {
        AutoReshardController controller =
                new AutoReshardController(policy());
        controller.decide(new LoadProbe(2000, 10, 100));
        assertThat(controller.decide(new LoadProbe(2000, 10, 100)))
                .isEqualTo(AutoReshardController.Decision.NOOP);
        Thread.sleep(60);
        assertThat(controller.decide(new LoadProbe(2000, 10, 100)))
                .isEqualTo(AutoReshardController.Decision.SPLIT);
    }

    @Test
    void circuitBreakerTripsAfterFailures() {
        AutoReshardController controller =
                new AutoReshardController(policy());
        for (int i = 0; i < 3; i++) {
            controller.onFailure();
        }
        assertThat(controller.tripped()).isTrue();
        assertThat(controller.decide(new LoadProbe(5000, 10, 100)))
                .isEqualTo(AutoReshardController.Decision.NOOP);
    }

    @Test
    void successResetsBreaker() {
        AutoReshardController controller =
                new AutoReshardController(policy());
        controller.onFailure();
        controller.onFailure();
        controller.onSuccess();
        assertThat(controller.tripped()).isFalse();
        assertThat(controller.failures()).isZero();
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {1, 2, 5})
    void parameterizedMaxFailures(int maxFailures) {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 50, maxFailures));
        for (int i = 0; i < maxFailures; i++) {
            controller.onFailure();
        }
        assertThat(controller.tripped()).isTrue();
    }

    @Test
    void zeroMaxFailuresRejected() {
        assertThatThrownBy(() -> new ReshardPolicy(1000, 100, 50, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "cooldown {0}")
    @ValueSource(longs = {0, 50, 500})
    void parameterizedCooldown(long cooldown) throws Exception {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, cooldown, 3));
        controller.decide(new LoadProbe(5000, 10, 100));
        AutoReshardController.Decision second =
                controller.decide(new LoadProbe(5000, 10, 100));
        if (cooldown == 0) {
            assertThat(second)
                    .isEqualTo(AutoReshardController.Decision.SPLIT);
        } else {
            assertThat(second)
                    .isEqualTo(AutoReshardController.Decision.NOOP);
        }
    }
}
