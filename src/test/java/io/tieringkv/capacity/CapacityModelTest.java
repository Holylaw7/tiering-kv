package io.tieringkv.capacity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 容量模型（ADR-0322）：四维估算 + 输入校验。 */
class CapacityModelTest {

    private static final CapacityModel.Input BASE =
            new CapacityModel.Input(10_000, 1_024, 0.8, 3, 7,
                    1_000_000);

    @Test
    void estimateComputesAllDimensions() {
        CapacityModel.Estimate estimate =
                CapacityModel.estimate(BASE);
        // 内存 = activeKeys * (value + overhead) * replicas
        double expectedMemory = 1_000_000L * (1_024 + 96) * 3;
        assertThat(estimate.memoryBytes())
                .isEqualTo(expectedMemory);
        // 磁盘 = qps * writeRatio * value * days * secs * replicas
        double expectedDisk = 10_000 * 0.2 * 1_024 * 7
                * 86_400 * 3;
        assertThat(estimate.diskBytes())
                .isCloseTo(expectedDisk, within(1.0));
        // 吞吐 = qps * 1.2
        assertThat(estimate.requiredQpsCapacity())
                .isEqualTo(12_000);
        // 读为主 → 5ms 预算
        assertThat(estimate.p99LatencyBudgetMs())
                .isEqualTo(5);
    }

    @Test
    void writeHeavyWorkloadUsesWriteBudget() {
        CapacityModel.Input writeHeavy =
                new CapacityModel.Input(10_000, 128, 0.2, 1, 1, 100);
        assertThat(CapacityModel.estimate(writeHeavy)
                .p99LatencyBudgetMs()).isEqualTo(10);
    }

    @Test
    void zeroInputsProduceZeroEstimates() {
        CapacityModel.Estimate estimate = CapacityModel.estimate(
                new CapacityModel.Input(0, 0, 0, 1, 0, 0));
        assertThat(estimate.memoryBytes()).isZero();
        assertThat(estimate.diskBytes()).isZero();
        assertThat(estimate.requiredQpsCapacity()).isZero();
    }

    @ParameterizedTest(name = "qps {0}")
    @ValueSource(doubles = {-1, -100})
    void negativeQpsRejected(double qps) {
        assertThatThrownBy(() -> new CapacityModel.Input(
                qps, 128, 0.5, 1, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidReadRatioRejected() {
        assertThatThrownBy(() -> new CapacityModel.Input(
                100, 128, 1.5, 1, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidReplicationRejected() {
        assertThatThrownBy(() -> new CapacityModel.Input(
                100, 128, 0.5, 0, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
