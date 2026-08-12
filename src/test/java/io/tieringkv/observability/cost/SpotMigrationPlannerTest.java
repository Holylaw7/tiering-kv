package io.tieringkv.observability.cost;

import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;
import io.tieringkv.observability.cost.SpotMigrationPlanner.MigrationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spot 中断迁移（ADR-0183）：迁移计划 + 幂等。 */
class SpotMigrationPlannerTest {

    private static final DataResidencyPolicy POLICY =
            new DataResidencyPolicy(Map.of(
                    "aws-us", "us", "gcp-us", "us"));

    private final SpotMigrationPlanner planner =
            new SpotMigrationPlanner();

    @Test
    void migratesToCheapestBackup() {
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(option("aws-us", 1, 0.0, 100, true),
                        option("gcp-us", 3, 0.0, 100, true)),
                task("t1", "us", 10, false), POLICY);
        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().fromCloud())
                .isEqualTo("aws-us");
        assertThat(plan.orElseThrow().toCloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void interruptedCloudExcluded() {
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(option("aws-us", 1, 0.0, 100, true)),
                task("t1", "us", 10, false), POLICY);
        assertThat(plan).isEmpty();
    }

    @Test
    void noBackupEmpty() {
        assertThat(planner.plan("t1", "aws-us", List.of(),
                task("t1", "us", 10, false), POLICY)).isEmpty();
    }

    @Test
    void sovereigntyConstraintFilters() {
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(option("aws-us", 1, 0.0, 100, true),
                        option("aws-eu", 1, 0.0, 100, true)),
                task("t1", "us", 10, false),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "aws-eu", "eu")));
        assertThat(plan).isEmpty();
    }

    @Test
    void quotaConstraintFilters() {
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(option("aws-us", 1, 0.0, 10, true),
                        option("gcp-us", 1, 0.0, 5, true)),
                task("t1", "us", 10, false), POLICY);
        assertThat(plan).isEmpty();
    }

    @Test
    void sloConstraintFilters() {
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(option("aws-us", 1, 0.0, 100, false),
                        option("gcp-us", 1, 0.0, 100, false)),
                task("t1", "us", 10, true), POLICY);
        assertThat(plan).isEmpty();
    }

    @Test
    void planIsDeterministic() {
        List<SpotOption> options = List.of(
                option("aws-us", 2, 0.8, 100, true),
                option("gcp-us", 4, 0.0, 100, true));
        MigrationPlan first = planner.plan("t1", "aws-us",
                options, task("t1", "us", 10, false), POLICY)
                .orElseThrow();
        MigrationPlan second = planner.plan("t1", "aws-us",
                options, task("t1", "us", 10, false), POLICY)
                .orElseThrow();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void penaltyAffectsBackupChoice() {
        SpotMigrationPlanner strict = new SpotMigrationPlanner(10.0);
        Optional<MigrationPlan> plan = strict.plan(
                "t1", "aws-us",
                List.of(option("aws-us", 1, 0.5, 100, true),
                        option("gcp-us", 5, 0.0, 100, true)),
                task("t1", "us", 10, false), POLICY);
        assertThat(plan.orElseThrow().toCloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void blankTaskIdRejected() {
        assertThatThrownBy(() -> planner.plan("", "aws-us",
                List.of(), task("t", "us", 10, false), POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankCloudRejected() {
        assertThatThrownBy(() -> planner.plan("t", "",
                List.of(), task("t", "us", 10, false), POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCandidatesRejected() {
        assertThatThrownBy(() -> planner.plan("t", "aws-us",
                null, task("t", "us", 10, false), POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRequirementsRejected() {
        assertThatThrownBy(() -> planner.plan("t", "aws-us",
                List.of(), null, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPolicyRejected() {
        assertThatThrownBy(() -> planner.plan("t", "aws-us",
                List.of(), task("t", "us", 10, false), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedCandidateCounts(int count) {
        List<SpotOption> options = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(option("c" + i, i + 1, 0.0, 100, true));
        }
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "c0", options,
                new SpotTask("t1", "default", 10, false),
                new DataResidencyPolicy(Map.of()));
        assertThat(plan.isPresent()).isEqualTo(count > 1);
        if (count > 1) {
            assertThat(plan.orElseThrow().toCloud())
                    .isNotEqualTo("c0");
        }
    }

    @ParameterizedTest(name = "penalty {0}")
    @ValueSource(doubles = {0.0, 1.0, 10.0})
    void parameterizedPenalties(double penalty) {
        SpotMigrationPlanner local = new SpotMigrationPlanner(
                penalty);
        Optional<MigrationPlan> plan = local.plan(
                "t1", "aws-us",
                List.of(option("aws-us", 1, 0.5, 100, true),
                        option("gcp-us", 5, 0.0, 100, true)),
                task("t1", "us", 10, false), POLICY);
        assertThat(plan).isPresent();
    }

    @Test
    void concurrentPlanningStable() throws Exception {
        List<SpotOption> options = List.of(
                option("aws-us", 2, 0.8, 100, true),
                option("gcp-us", 4, 0.0, 100, true));
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    Optional<MigrationPlan> plan = planner.plan(
                            "t", "aws-us", options,
                            task("t", "us", 10, false), POLICY);
                    assertThat(plan.orElseThrow().toCloud())
                            .isEqualTo("gcp-us");
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static SpotOption option(String cloud, double price,
                                     double rate, long quota,
                                     boolean slo) {
        return new SpotOption(cloud, price, rate, quota, slo);
    }

    private static SpotTask task(String taskId, String residency,
                                 long quota, boolean slo) {
        return new SpotTask(taskId, residency, quota, slo);
    }
}
