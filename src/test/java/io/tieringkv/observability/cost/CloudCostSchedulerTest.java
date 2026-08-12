package io.tieringkv.observability.cost;

import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.observability.cost.CloudCostScheduler.CloudOption;
import io.tieringkv.observability.cost.CloudCostScheduler.ScheduleTask;
import io.tieringkv.observability.cost.CloudCostScheduler.SchedulingDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多云成本调度（ADR-0168）：竞价 + 主权/配额/SLO 约束。 */
class CloudCostSchedulerTest {

    private static final DataResidencyPolicy POLICY =
            new DataResidencyPolicy(Map.of(
                    "aws-us", "us", "gcp-us", "us",
                    "aws-eu", "eu"));

    private final CloudCostScheduler scheduler =
            new CloudCostScheduler();

    @Test
    void cheapestValidCloudSelected() {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 5, 100, true),
                        option("gcp-us", 3, 100, true)),
                POLICY);
        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().cloud()).isEqualTo("gcp-us");
        assertThat(decision.orElseThrow().pricePerUnit())
                .isEqualTo(3);
    }

    @Test
    void sovereigntyConstraintFilters() {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 9, 100, true),
                        option("aws-eu", 1, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
    }

    @Test
    void quotaConstraintFilters() {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", 50, false),
                List.of(option("aws-us", 1, 40, true),
                        option("gcp-us", 2, 60, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void sloConstraintFilters() {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", 10, true),
                List.of(option("aws-us", 1, 100, false),
                        option("gcp-us", 2, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void noCandidateEmpty() {
        assertThat(scheduler.schedule(task("t1", "us", 10, false),
                List.of(option("aws-eu", 1, 100, true)),
                POLICY)).isEmpty();
    }

    @Test
    void quotaExceededEmpty() {
        assertThat(scheduler.schedule(task("t1", "us", 200, false),
                List.of(option("aws-us", 1, 100, true)),
                POLICY)).isEmpty();
    }

    @Test
    void sloUnmetEmpty() {
        assertThat(scheduler.schedule(task("t1", "us", 10, true),
                List.of(option("aws-us", 1, 100, false)),
                POLICY)).isEmpty();
    }

    @Test
    void tieKeepsFirst() {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 3, 100, true),
                        option("gcp-us", 3, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
    }

    @Test
    void zeroPriceCloudWins() {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", 0, 100, true),
                        option("gcp-us", 5, 100, true)),
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
    void blankCloudRejected() {
        assertThatThrownBy(() -> option("", 1, 100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativePriceRejected() {
        assertThatThrownBy(() -> option("aws-us", -1, 100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeQuotaRejected() {
        assertThatThrownBy(() -> option("aws-us", 1, -1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTaskIdRejected() {
        assertThatThrownBy(() -> new ScheduleTask("", "us", 10,
                false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankResidencyRejected() {
        assertThatThrownBy(() -> new ScheduleTask("t1", "", 10,
                false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeRequiredQuotaRejected() {
        assertThatThrownBy(() -> new ScheduleTask("t1", "us", -1,
                false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.5, 3.0, 10.0})
    void parameterizedPrices(double price) {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", 10, false),
                List.of(option("aws-us", price, 100, true),
                        option("gcp-us", price * 2, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
        assertThat(decision.orElseThrow().pricePerUnit())
                .isEqualTo(price);
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(longs = {0, 50, 100})
    void parameterizedRequiredQuotas(long quota) {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "us", quota, false),
                List.of(option("aws-us", 1, 100, true)),
                POLICY);
        assertThat(decision).isPresent();
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedCandidateCounts(int count) {
        List<CloudOption> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(option("cloud-" + i, i + 1, 100, true));
        }
        DataResidencyPolicy permissive = new DataResidencyPolicy(
                Map.of());
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("t1", "default", 10, false), candidates,
                permissive);
        assertThat(decision.orElseThrow().pricePerUnit())
                .isEqualTo(1);
    }

    @Test
    void concurrentSchedulingStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    Optional<SchedulingDecision> decision =
                            scheduler.schedule(
                                    task("t", "us", 10, false),
                                    List.of(
                                            option("aws-us", 5,
                                                    100, true),
                                            option("gcp-us", 3,
                                                    100, true)),
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

    @Test
    void decisionCarriesTaskIndependentCloud() {
        Optional<SchedulingDecision> decision = scheduler.schedule(
                task("task-42", "us", 10, false),
                List.of(option("gcp-us", 2, 100, true)),
                POLICY);
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    private static ScheduleTask task(String taskId,
                                     String residency,
                                     long quota, boolean slo) {
        return new ScheduleTask(taskId, residency, quota, slo);
    }

    private static CloudOption option(String cloud, double price,
                                      long quota, boolean slo) {
        return new CloudOption(cloud, price, quota, slo);
    }
}
