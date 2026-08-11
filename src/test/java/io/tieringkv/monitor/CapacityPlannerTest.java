package io.tieringkv.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 容量模型（Goal 8）：存储/QPS 双维度节点估算。 */
class CapacityPlannerTest {

    @Test
    void storageDrivenNodes() {
        CapacityPlanner.CapacityEstimate estimate =
                new CapacityPlanner().estimate(8, 1000, 100_000,
                        100, 100_000);
        assertThat(estimate.nodes()).isEqualTo(10);
    }

    @Test
    void qpsDrivenNodes() {
        CapacityPlanner.CapacityEstimate estimate =
                new CapacityPlanner().estimate(8, 100, 1_000_000,
                        1000, 100_000);
        assertThat(estimate.nodes()).isEqualTo(10);
    }

    @Test
    void atLeastOneNode() {
        CapacityPlanner.CapacityEstimate estimate =
                new CapacityPlanner().estimate(1, 0, 0, 100, 100);
        assertThat(estimate.nodes()).isEqualTo(1);
    }

    @ParameterizedTest(name = "qps {0}")
    @ValueSource(longs = {1_000, 1_000_000})
    void parameterizedQpsCapacity(long qps) {
        CapacityPlanner.CapacityEstimate estimate =
                new CapacityPlanner().estimate(4, 100, qps,
                        1000, 100_000);
        assertThat(estimate.qps()).isEqualTo(qps);
        assertThat(estimate.nodes()).isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(longs = {100, 10_000})
    void parameterizedStorageCapacity(long storageGB) {
        CapacityPlanner.CapacityEstimate estimate =
                new CapacityPlanner().estimate(4, storageGB, 10_000,
                        100, 100_000);
        assertThat(estimate.storageGB()).isEqualTo(storageGB);
    }
}
