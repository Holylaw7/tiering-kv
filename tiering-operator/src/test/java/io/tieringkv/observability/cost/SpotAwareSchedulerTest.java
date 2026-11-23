package io.tieringkv.observability.cost;

import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotDecision;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spot 感知调度（ADR-0175）：期望成本竞价。 */
class SpotAwareSchedulerTest {

    private static final DataResidencyPolicy POLICY =
            new DataResidencyPolicy(Map.of(
                    "aws-us", "us", "gcp-us", "us"));

    private final SpotAwareScheduler scheduler =
            new SpotAwareScheduler();

    @Test
    void cheapStableSpotWins() {
        Optional<SpotDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 5, 0.0, 100, true),
                        option("gcp-us", 3, 0.0, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
        assertThat(decision.orElseThrow().expectedCost())
                .isEqualTo(3);
    }

    @Test
    void highInterruptionSpotLosesToOnDemand() {
        Optional<SpotDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 2, 0.8, 100, true),
                        option("gcp-us", 4, 0.0, 100, true)),
                POLICY);
        // aws expected = 2*(1+1.6)=5.2; gcp = 4
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void expectedCostFormula() {
        assertThat(scheduler.expectedCost(
                option("aws-us", 10, 0.5, 100, true)))
                .isEqualTo(20);
        assertThat(scheduler.expectedCost(
                option("aws-us", 10, 0.0, 100, true)))
                .isEqualTo(10);
    }

    @Test
    void sovereigntyConstraintFilters() {
        Optional<SpotDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 9, 0.0, 100, true),
                        option("aws-eu", 1, 0.0, 100, true)),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "aws-eu", "eu")));
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
    }

    @Test
    void quotaConstraintFilters() {
        Optional<SpotDecision> decision = scheduler.schedule(
                task("t1", "us", 50, false),
                List.of(option("aws-us", 1, 0.0, 40, true),
                        option("gcp-us", 2, 0.0, 60, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void sloConstraintFilters() {
        Optional<SpotDecision> decision = scheduler.schedule(
                task("t1", "us", 10, true),
                List.of(option("aws-us", 1, 0.0, 100, false),
                        option("gcp-us", 2, 0.0, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void noCandidateEmpty() {
        assertThat(scheduler.schedule(task("t1", "us", 10, false),
                List.of(option("aws-eu", 1, 0.0, 100, true)),
                new DataResidencyPolicy(Map.of(
                        "aws-eu", "eu")))).isEmpty();
    }

    @Test
    void customPenaltyAffectsChoice() {
        SpotAwareScheduler strict = new SpotAwareScheduler(10.0);
        Optional<SpotDecision> decision = strict.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 1, 0.5, 100, true),
                        option("gcp-us", 5, 0.0, 100, true)),
                POLICY);
        // aws expected = 1*(1+5)=6; gcp = 5
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void zeroPenaltyIgnoresInterruption() {
        SpotAwareScheduler zero = new SpotAwareScheduler(0.0);
        Optional<SpotDecision> decision = zero.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 2, 0.9, 100, true),
                        option("gcp-us", 4, 0.0, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
    }

    @Test
    void nullTaskRejected() {
        assertThatThrownBy(() -> scheduler.schedule(null,
                List.of(), POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCandidatesRejected() {
        assertThatThrownBy(() -> scheduler.schedule(
                task("t1", "us", 10, false), null, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPolicyRejected() {
        assertThatThrownBy(() -> scheduler.schedule(
                task("t1", "us", 10, false), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullOptionRejected() {
        assertThatThrownBy(() -> scheduler.expectedCost(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankCloudRejected() {
        assertThatThrownBy(() -> option("", 1, 0.0, 100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidInterruptionRateRejected() {
        assertThatThrownBy(() -> option("aws-us", 1, 1.5, 100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativePenaltyRejected() {
        assertThatThrownBy(() -> new SpotAwareScheduler(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.5, 0.9})
    void parameterizedInterruptionRates(double rate) {
        double expected = 10 * (1 + rate * 2);
        assertThat(scheduler.expectedCost(
                option("aws-us", 10, rate, 100, true)))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.5, 3.0, 10.0})
    void parameterizedPrices(double price) {
        Optional<SpotDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", price, 0.0, 100, true),
                        option("gcp-us", price * 2, 0.0, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
    }

    @ParameterizedTest(name = "penalty {0}")
    @ValueSource(doubles = {0.0, 1.0, 5.0})
    void parameterizedPenalties(double penalty) {
        SpotAwareScheduler local = new SpotAwareScheduler(penalty);
        assertThat(local.expectedCost(
                option("aws-us", 10, 0.5, 100, true)))
                .isEqualTo(10 * (1 + 0.5 * penalty));
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedCandidateCounts(int count) {
        List<SpotOption> options = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(option("c" + i, i + 1, 0.0, 100, true));
        }
        Optional<SpotDecision> decision = scheduler.schedule(
                task("t1", "default", 10, false), options,
                new DataResidencyPolicy(Map.of()));
        assertThat(decision.orElseThrow().expectedCost())
                .isEqualTo(1);
    }

    @Test
    void concurrentSchedulingStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    Optional<SpotDecision> decision =
                            scheduler.schedule(
                                    task("t", "us", 10, false),
                                    List.of(
                                            option("aws-us", 2,
                                                    0.8, 100,
                                                    true),
                                            option("gcp-us", 4,
                                                    0.0, 100,
                                                    true)),
                                    POLICY);
                    assertThat(decision.orElseThrow().cloud())
                            .isEqualTo("gcp-us");
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static SpotTask task(String taskId, String residency,
                                 long quota, boolean slo) {
        return new SpotTask(taskId, residency, quota, slo);
    }

    private static SpotOption option(String cloud, double price,
                                     double rate, long quota,
                                     boolean slo) {
        return new SpotOption(cloud, price, rate, quota, slo);
    }
}
