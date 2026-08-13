package io.tieringkv.platform;

import io.tieringkv.operations.ProductCompletenessBaseline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** GA 最终质量门禁（ADR-0310）。 */
class GaQualityGateTest {

    @Test
    void gaBaselineReady() {
        assertThat(ProductCompletenessBaseline.gaReady()).isTrue();
    }

    @Test
    void coverageGateConfigured() throws Exception {
        assertThat(Files.readString(Path.of("scripts",
                "coverage-check.sh"))).contains("COVERAGE_THRESHOLD");
    }

    @Test
    void benchmarkSummaryExists() {
        assertThat(Path.of("docs", "benchmark",
                "ga-final-benchmark-summary.md").toFile()).exists();
    }

    @Test
    void jepsenHarnessDocExists() {
        assertThat(Path.of("docs", "distributed",
                "jepsen-harness.md").toFile()).exists();
    }

    @Test
    void federationDocExists() {
        assertThat(Path.of("docs", "distributed",
                "multi-cluster-federation.md").toFile()).exists();
    }

    @ParameterizedTest(name = "doc {0}")
    @MethodSource("gaDocs")
    void gaDocsPresent(String path) {
        assertThat(Path.of(path).toFile()).exists();
    }

    static Stream<Arguments> gaDocs() {
        return Stream.of(
                        "docs/review/phase56-ga-finalization-review.md",
                        "docs/deployment/real-runner-final-review.md",
                        "docs/design/consumer-group-advanced.md",
                        "docs/operations/ga-operations-closure.md",
                        "docs/review/product-completeness-baseline-v2.md",
                        "docs/release/archive/ga-release-archive.md")
                .map(Arguments::of);
    }
}
