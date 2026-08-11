package io.tieringkv.platform;

import io.tieringkv.sharding.auto.AutoReshardController;
import io.tieringkv.sharding.auto.LoadProbe;
import io.tieringkv.sharding.auto.ReshardPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 31 自动重分片边缘：判定/冷却/熔断参数矩阵。 */
class Phase31AutoReshardEdgeTest {

    @ParameterizedTest(name = "qps {0}")
    @ValueSource(longs = {10, 50, 100, 200, 500, 1000, 1500, 2000,
            5000, 10000, 50000, 100000, 500000, 1000000, 2000000})
    void qpsDecisionMatrix(long qps) {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 0, 3));
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

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {1, 2, 4, 5, 8, 10, 20, 50, 100, 200,
            500, 1000, 2000, 5000, 10000})
    void failureThresholdMatrix(int maxFailures) {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 0, maxFailures));
        for (int i = 0; i < maxFailures; i++) {
            controller.onFailure();
        }
        assertThat(controller.tripped()).isTrue();
    }

    @Test
    void boundaryQpsNoop() {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 0, 3));
        assertThat(controller.decide(new LoadProbe(100, 5, 100)))
                .isEqualTo(AutoReshardController.Decision.NOOP);
        assertThat(controller.decide(new LoadProbe(1000, 5, 100)))
                .isEqualTo(AutoReshardController.Decision.NOOP);
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(longs = {0, 1, 100, 100000})
    void shardSizeBoundaries(long size) {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 0, 3));
        assertThat(controller.decide(new LoadProbe(
                500, 5, size))).isNotNull();
    }

    @ParameterizedTest(name = "latency {0}")
    @ValueSource(longs = {0, 5, 100, 1000})
    void latencyBoundaries(long latency) {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 0, 3));
        assertThat(controller.decide(new LoadProbe(
                500, latency, 100))).isNotNull();
    }
}
