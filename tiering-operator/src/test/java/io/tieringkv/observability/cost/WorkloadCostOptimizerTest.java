package io.tieringkv.observability.cost;

import io.tieringkv.observability.cost.CostAttribution.CostEntry;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.Risk;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.Suggestion;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.SuggestionType;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.WorkloadProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Workload 成本优化（ADR-0160）：建议 + 收益 + 风险。 */
class WorkloadCostOptimizerTest {

    private final WorkloadCostOptimizer optimizer =
            new WorkloadCostOptimizer();

    @Test
    void healthyWorkloadNoSuggestions() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        1000, 500, 10, 1),
                100);
        assertThat(suggestions).isEmpty();
    }

    @Test
    void lowActivityScaleDown() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        10, 5, 20, 1),
                100);
        assertThat(suggestions).extracting(Suggestion::type)
                .contains(SuggestionType.SCALE_DOWN);
        assertThat(suggestions.get(0).estimatedSavings())
                .isEqualTo(30);
    }

    @Test
    void largeStorageScaleDownHigherRisk() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        10, 5, 200, 1),
                100);
        Suggestion scaleDown = suggestions.stream()
                .filter(s -> s.type() == SuggestionType.SCALE_DOWN)
                .findFirst().orElseThrow();
        assertThat(scaleDown.risk()).isEqualTo(Risk.MEDIUM);
    }

    @Test
    void writeHeavyColdTier() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        100, 900, 80, 1),
                100);
        assertThat(suggestions).extracting(Suggestion::type)
                .contains(SuggestionType.COLD_TIER);
        assertThat(suggestions.stream()
                .filter(s -> s.type() == SuggestionType.COLD_TIER)
                .findFirst().orElseThrow().estimatedSavings())
                .isEqualTo(50);
    }

    @Test
    void largeValuesCompression() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        1000, 500, 10, 128),
                100);
        assertThat(suggestions).extracting(Suggestion::type)
                .contains(SuggestionType.COMPRESSION);
    }

    @Test
    void multipleSuggestionsTogether() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        5, 90, 100, 128),
                100);
        assertThat(suggestions).extracting(Suggestion::type)
                .containsExactlyInAnyOrder(SuggestionType.SCALE_DOWN,
                        SuggestionType.COLD_TIER,
                        SuggestionType.COMPRESSION);
    }

    @Test
    void nullProfileRejected() {
        assertThatThrownBy(() -> optimizer.analyze(null, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeCostRejected() {
        assertThatThrownBy(() -> optimizer.analyze(
                new WorkloadProfile("t1", "d", "c", 1, 1, 1, 1),
                -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroCostZeroSavings() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        10, 5, 20, 128),
                0);
        assertThat(suggestions).allMatch(
                s -> s.estimatedSavings() == 0);
    }

    @Test
    void blankTenantRejected() {
        assertThatThrownBy(() -> new WorkloadProfile("", "d", "c",
                1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeMetricsRejected() {
        assertThatThrownBy(() -> new WorkloadProfile("t1", "d", "c",
                -1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void analyzeAllUsesCostAttribution() {
        CostAttribution costs = new CostAttribution();
        costs.add(new CostEntry("t1", "orders", "aws-us",
                "storage", 100));
        List<Suggestion> suggestions = optimizer.analyzeAll(Map.of(
                "t1", new WorkloadProfile("t1", "orders", "aws-us",
                        10, 5, 20, 1)),
                costs);
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).estimatedSavings())
                .isEqualTo(30);
    }

    @Test
    void analyzeAllNullRejected() {
        assertThatThrownBy(() -> optimizer.analyzeAll(null,
                new CostAttribution()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> optimizer.analyzeAll(Map.of(),
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidThresholdsRejected() {
        assertThatThrownBy(() -> new WorkloadCostOptimizer(
                -1, 0.5, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkloadCostOptimizer(
                10, 1.5, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkloadCostOptimizer(
                10, 0.5, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(longs = {0, 50, 200})
    void parameterizedActivityThresholds(long ops) {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        ops, ops / 2, 20, 1),
                100);
        boolean scaleDown = suggestions.stream().anyMatch(
                s -> s.type() == SuggestionType.SCALE_DOWN);
        assertThat(scaleDown).isEqualTo(ops < 100);
    }

    @ParameterizedTest(name = "write ratio {0}")
    @ValueSource(longs = {10, 50, 400, 900})
    void parameterizedWriteRatios(long writes) {
        long reads = 100;
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        reads, writes, 80, 1),
                100);
        boolean coldTier = suggestions.stream().anyMatch(
                s -> s.type() == SuggestionType.COLD_TIER);
        double ratio = (double) writes / (reads + writes);
        assertThat(coldTier).isEqualTo(ratio >= 0.8);
    }

    @ParameterizedTest(name = "value KB {0}")
    @ValueSource(longs = {1, 63, 64, 128})
    void parameterizedValueSizes(long sizeKB) {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        1000, 500, 10, sizeKB),
                100);
        boolean compression = suggestions.stream().anyMatch(
                s -> s.type() == SuggestionType.COMPRESSION);
        assertThat(compression).isEqualTo(sizeKB >= 64);
    }

    @ParameterizedTest(name = "storage {0}")
    @CsvSource({"10,false", "49,false", "50,true", "200,true"})
    void parameterizedColdTierStorage(long storageGB,
                                      boolean suggested) {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        100, 900, storageGB, 1),
                100);
        boolean coldTier = suggestions.stream().anyMatch(
                s -> s.type() == SuggestionType.COLD_TIER);
        assertThat(coldTier).isEqualTo(suggested);
    }

    @Test
    void riskLowForCompression() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        1000, 500, 10, 128),
                100);
        assertThat(suggestions.stream()
                .filter(s -> s.type() == SuggestionType.COMPRESSION)
                .findFirst().orElseThrow().risk())
                .isEqualTo(Risk.LOW);
    }

    @Test
    void coldTierRiskMedium() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        100, 900, 80, 1),
                100);
        assertThat(suggestions.stream()
                .filter(s -> s.type() == SuggestionType.COLD_TIER)
                .findFirst().orElseThrow().risk())
                .isEqualTo(Risk.MEDIUM);
    }

    @Test
    void concurrentAnalyzeStable() throws Exception {
        WorkloadProfile profile = new WorkloadProfile(
                "t1", "orders", "aws-us", 5, 90, 100, 128);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(optimizer.analyze(profile, 100))
                            .hasSize(3);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @Test
    void suggestionsCarryReason() {
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        10, 5, 20, 1),
                100);
        assertThat(suggestions.get(0).reason())
                .contains("low activity");
    }
}
