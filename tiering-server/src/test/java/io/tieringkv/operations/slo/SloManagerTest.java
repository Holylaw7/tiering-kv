package io.tieringkv.operations.slo;

import io.tieringkv.operations.slo.SloManager.SloDefinition;
import io.tieringkv.operations.slo.SloManager.SloSnapshot;
import io.tieringkv.operations.slo.SloManager.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SLO 管理（ADR-0162）：滚动窗口 + 达成率 + 状态。 */
class SloManagerTest {

    @Test
    void emptyWindowComplianceOne() {
        SloManager manager = manager();
        assertThat(manager.compliance("slo-1")).isEqualTo(1.0);
        assertThat(manager.status("slo-1"))
                .isEqualTo(Status.COMPLIANT);
    }

    @Test
    void allSuccessComplianceOne() {
        SloManager manager = manager();
        for (int i = 0; i < 10; i++) {
            manager.record("slo-1", true);
        }
        assertThat(manager.compliance("slo-1")).isEqualTo(1.0);
    }

    @Test
    void mixedCompliance() {
        SloManager manager = manager();
        for (int i = 0; i < 8; i++) {
            manager.record("slo-1", true);
        }
        for (int i = 0; i < 2; i++) {
            manager.record("slo-1", false);
        }
        assertThat(manager.compliance("slo-1")).isEqualTo(0.8);
    }

    @Test
    void windowRollsOldSamplesOut() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 5));
        for (int i = 0; i < 5; i++) {
            manager.record("s", false);
        }
        assertThat(manager.compliance("s")).isZero();
        for (int i = 0; i < 5; i++) {
            manager.record("s", true);
        }
        assertThat(manager.compliance("s")).isEqualTo(1.0);
    }

    @Test
    void breachedStatusBelowTarget() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", i < 5);
        }
        assertThat(manager.status("s")).isEqualTo(Status.BREACHED);
    }

    @Test
    void atRiskWithinBand() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", i < 8);
        }
        assertThat(manager.status("s")).isEqualTo(Status.AT_RISK);
    }

    @Test
    void snapshotCarriesWindowSize() {
        SloManager manager = manager();
        SloSnapshot snapshot = manager.snapshot("slo-1");
        assertThat(snapshot.windowSize()).isEqualTo(10);
    }

    @Test
    void unknownSloRejected() {
        SloManager manager = manager();
        assertThatThrownBy(() -> manager.record("missing", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.compliance("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateDefineRejected() {
        SloManager manager = manager();
        assertThatThrownBy(() -> manager.define(
                new SloDefinition("slo-1", "latency", 0.9, 10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetClearsWindow() {
        SloManager manager = manager();
        manager.record("slo-1", false);
        manager.reset("slo-1");
        assertThat(manager.compliance("slo-1")).isEqualTo(1.0);
    }

    @Test
    void nullDefinitionRejected() {
        assertThatThrownBy(() -> manager().define(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sloIdsListed() {
        SloManager manager = manager();
        assertThat(manager.sloIds()).containsExactly("slo-1");
    }

    @Test
    void blankSloIdRejected() {
        assertThatThrownBy(() -> new SloDefinition("", "latency",
                0.9, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankMetricRejected() {
        assertThatThrownBy(() -> new SloDefinition("s", " ",
                0.9, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTargetRejected() {
        assertThatThrownBy(() -> new SloDefinition("s", "latency",
                -0.1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SloDefinition("s", "latency",
                1.1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidWindowRejected() {
        assertThatThrownBy(() -> new SloDefinition("s", "latency",
                0.9, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(doubles = {0.5, 0.9, 0.99})
    void parameterizedTargets(double target) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency",
                target, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", true);
        }
        assertThat(manager.status("s")).isEqualTo(Status.COMPLIANT);
    }

    @ParameterizedTest(name = "window {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedWindows(int window) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.5,
                window));
        for (int i = 0; i < window; i++) {
            manager.record("s", true);
        }
        assertThat(manager.compliance("s")).isEqualTo(1.0);
        assertThat(manager.snapshot("s").windowSize())
                .isEqualTo(window);
    }

    @ParameterizedTest(name = "success {0} of {1}")
    @CsvSource({"10,10", "9,10", "8,10", "5,10", "0,10"})
    void parameterizedCompliance(int success, int total) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9,
                total));
        for (int i = 0; i < total; i++) {
            manager.record("s", i < success);
        }
        double expected = (double) success / total;
        assertThat(manager.compliance("s")).isEqualTo(expected);
    }

    @Test
    void concurrentRecordsStable() throws Exception {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 100));
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    manager.record("s", i % 10 != 0);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(manager.compliance("s")).isBetween(0.8, 1.0);
        assertThat(manager.snapshot("s").windowSize()).isEqualTo(100);
    }

    @Test
    void complianceNeverExceedsOne() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 3));
        for (int i = 0; i < 10; i++) {
            manager.record("s", true);
        }
        assertThat(manager.compliance("s")).isEqualTo(1.0);
    }

    private static SloManager manager() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("slo-1", "latency",
                0.9, 10));
        return manager;
    }
}
