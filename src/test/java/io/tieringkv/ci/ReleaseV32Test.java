package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** v3.2.0 冻结与发布流水线（ADR-0261）。 */
class ReleaseV32Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v32TagRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.2.0-rc*");
        assertThat(content).contains("v3.2.0");
    }

    @Test
    void v31TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.1.0-rc*");
        assertThat(content).contains("v3.1.0");
    }

    @Test
    void benchmarkRunsPhase49Suite() throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = map(doc.get("jobs"));
        Map<String, Object> release = map(jobs.get("release"));
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) release.get("steps");
        Map<String, Object> benchmark = steps.stream()
                .filter(step -> "Benchmark".equals(
                        step.get("name")))
                .findFirst().orElseThrow();
        assertThat(benchmark.get("run").toString())
                .contains("Phase49BenchmarkTest");
        assertThat(benchmark.get("run").toString())
                .contains("Phase49ProductionBaselineTest");
    }

    @Test
    void v32ReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.2.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV32() throws Exception {
        String content = Files.readString(
                Path.of("CHANGELOG.md"));
        assertThat(content).contains("3.2.0");
    }

    @Test
    void roadmapDocumentsV32() throws Exception {
        String content = Files.readString(Path.of("ROADMAP.md"));
        assertThat(content).contains("3.2.0");
    }

    @Test
    void releaseNotesScriptCoversV32() throws Exception {
        String content = Files.readString(
                Path.of("scripts", "release-notes.sh"));
        assertThat(content).contains("v3.2.0-rc1");
    }

    @Test
    void adr261Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0261-v3.2-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void gateConvergenceV15DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "gate-convergence-v15.md").toFile()).exists();
    }

    @Test
    void tikvRegressionArchiveDocPresent() {
        assertThat(Path.of("docs", "benchmark",
                "tikv-regression-archive.md").toFile()).exists();
    }

    @Test
    void crossRegulatoryDocPresent() {
        assertThat(Path.of("docs", "transaction",
                "cross-regulatory-federation.md").toFile())
                .exists();
    }

    @Test
    void federatedLearningDocPresent() {
        assertThat(Path.of("docs", "sql",
                "federated-learning-pushdown.md").toFile())
                .exists();
    }

    @Test
    void realCredentialsV7DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "real-credentials-validation-v7.md").toFile())
                .exists();
    }

    @ParameterizedTest(name = "tag {0}")
    @MethodSource("releaseTags")
    void everyFrozenVersionTagRegistered(String tag)
            throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains(tag);
    }

    @ParameterizedTest(name = "benchmark class {0}")
    @MethodSource("benchmarkClasses")
    void benchmarkSuiteIncludes(String className)
            throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains(className);
    }

    @ParameterizedTest(name = "ADR {0}")
    @MethodSource("adrFiles")
    void phase49AdrsPresent(String fileName) {
        assertThat(Path.of("docs", "adr", fileName).toFile())
                .exists();
    }

    static Stream<String> releaseTags() {
        return Stream.of("v1.0.0", "v1.1.0", "v1.2.0", "v1.3.0",
                        "v1.4.0", "v1.5.0", "v1.6.0", "v1.7.0",
                        "v1.8.0", "v1.9.0", "v2.0.0", "v2.1.0",
                        "v2.2.0", "v2.3.0", "v2.4.0", "v2.5.0",
                        "v2.6.0", "v2.7.0", "v2.8.0", "v2.9.0",
                        "v3.0.0", "v3.1.0", "v3.2.0")
                .flatMap(version -> Stream.of(
                        version + "-rc*", version));
    }

    static Stream<String> benchmarkClasses() {
        return Stream.concat(
                java.util.stream.IntStream.rangeClosed(24, 49)
                        .mapToObj(phase -> "Phase"
                                + phase + "BenchmarkTest"),
                java.util.stream.IntStream.rangeClosed(43, 49)
                        .mapToObj(phase -> "Phase"
                                + phase
                                + "ProductionBaselineTest"));
    }

    static Stream<String> adrFiles() {
        return Stream.of(
                "ADR-0255-real-runner-gate-convergence-v15.md",
                "ADR-0256-cross-regulatory-federation-scale-out.md",
                "ADR-0257-federated-learning-multi-agent-pushdown.md",
                "ADR-0258-commercial-quantum-satellite-tso-integration.md",
                "ADR-0259-regulatory-knowledge-base-and-diff-reporting.md",
                "ADR-0260-tikv-regression-archive-and-real-credentials-v7.md",
                "ADR-0261-v3.2-freeze-and-release-pipeline.md");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml() throws Exception {
        try (var reader = Files.newBufferedReader(WORKFLOW)) {
            Object loaded = new Yaml().load(reader);
            return (Map<String, Object>) loaded;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
