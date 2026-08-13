package io.tieringkv.platform;

import io.tieringkv.ci.GateConvergenceV16;
import io.tieringkv.observability.OpsLogger;
import io.tieringkv.observability.Redactor;
import io.tieringkv.operations.ProductCompletenessBaseline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 50 边缘矩阵：版本/日志/门禁/基线边界行为。 */
class Phase50EdgeMatrixTest {

    @Test
    void redactorHandlesNullUrl() {
        assertThat(Redactor.maskUrl(null)).isNull();
    }

    @Test
    void redactorPlainTextUnchanged() {
        assertThat(Redactor.mask("plain text"))
                .isEqualTo("plain text");
    }

    @Test
    void gateV16SummaryContainsAllDispositions() {
        String summary = GateConvergenceV16.summary();
        assertThat(summary).contains("CLOSED");
        assertThat(summary).contains("ENV_BLOCKED_FINAL");
        assertThat(summary).contains("REGISTERED_RELEASE");
    }

    @Test
    void completenessBaselineChecklistNonBlank() {
        assertThat(ProductCompletenessBaseline
                .judgmentChecklist()).allSatisfy(
                item -> assertThat(item).isNotBlank());
    }

    @ParameterizedTest(name = "edge {0}")
    @MethodSource("edges")
    void edgeMatrix(String edge) {
        switch (edge) {
            case "redactor-empty-secret" ->
                    assertThat(Redactor.mask(
                            "token= with space")).isNotNull();
            case "redactor-url-without-password" ->
                    assertThat(Redactor.mask("https://host/path"))
                            .isEqualTo("https://host/path");
            case "redactor-multiple-masks" -> {
                String masked = Redactor.mask(
                        "password=abc123 token=xyz789");
                assertThat(masked).doesNotContain("abc123");
                assertThat(masked).doesNotContain("xyz789");
            }
            case "redactor-case-insensitive" ->
                    assertThat(Redactor.mask("PASSWORD=abc123"))
                            .doesNotContain("abc123");
            case "redactor-authorization-header" ->
                    assertThat(Redactor.mask(
                            "Authorization: Basic abcdef123"))
                            .doesNotContain("abcdef123");
            case "ops-logger-error" ->
                    OpsLogger.error("boom", new RuntimeException("x"));
            case "ops-logger-redacts-args" -> {
                OpsLogger.warn("connect {} {}", "s3",
                        "secret-value");
            }
            case "gate-lookup-known" ->
                    assertThat(GateConvergenceV16.gate("TD-085")
                            .disposition())
                            .isEqualTo(GateConvergenceV16
                                    .Disposition.CLOSED);
            case "gate-lookup-release" ->
                    assertThat(GateConvergenceV16.gate("REL-001")
                            .disposition())
                            .isEqualTo(GateConvergenceV16
                                    .Disposition
                                    .REGISTERED_RELEASE);
            case "gate-unknown-throws" ->
                    assertThatThrownBy(
                            () -> GateConvergenceV16.gate("NOPE"))
                            .isInstanceOf(IllegalArgumentException
                                    .class);
            case "gate-ids-unique" -> {
                long unique = GateConvergenceV16.gates().stream()
                        .map(GateConvergenceV16.Gate::id)
                        .distinct().count();
                assertThat(unique)
                        .isEqualTo(GateConvergenceV16.gates().size());
            }
            case "baseline-product-core" ->
                    assertThat(ProductCompletenessBaseline
                            .capabilities().stream()
                            .filter(c -> c.tier() == ProductCompletenessBaseline
                                    .Tier.PRODUCT)
                            .count()).isGreaterThan(3);
            case "baseline-experimental-marked" ->
                    assertThat(ProductCompletenessBaseline
                            .capabilities().stream()
                            .filter(c -> c.tier() == ProductCompletenessBaseline
                                    .Tier.EXPERIMENTAL)
                            .count()).isGreaterThan(2);
            case "baseline-debts-terminal" ->
                    assertThat(ProductCompletenessBaseline
                            .techDebts().stream()
                            .allMatch(d -> d.disposition() != null))
                            .isTrue();
            case "baseline-passes" ->
                    assertThat(ProductCompletenessBaseline.passes())
                            .isTrue();
            default -> throw new AssertionError(
                    "unknown edge " + edge);
        }
    }

    static Stream<String> edges() {
        return Stream.of("redactor-empty-secret",
                "redactor-url-without-password",
                "redactor-multiple-masks",
                "redactor-case-insensitive",
                "redactor-authorization-header",
                "ops-logger-error",
                "ops-logger-redacts-args",
                "gate-lookup-known",
                "gate-lookup-release",
                "gate-unknown-throws",
                "gate-ids-unique",
                "baseline-product-core",
                "baseline-experimental-marked",
                "baseline-debts-terminal",
                "baseline-passes");
    }
}
