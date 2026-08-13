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

/** v3.2.0 GA 发布流水线（ADR-0266）。 */
class ReleaseV32GATest {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v32GATagsRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.2.0-rc*");
        assertThat(content).contains("v3.2.0");
    }

    @Test
    void benchmarkRunsPhase50Suite() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("Phase50BenchmarkTest");
        assertThat(content).contains("Phase50ProductionBaselineTest");
    }

    @Test
    void checksumsStepPresent() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("sha256sum");
    }

    @Test
    void gaReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.2.0-ga-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV32Ga() throws Exception {
        assertThat(Files.readString(Path.of("CHANGELOG.md")))
                .contains("3.2.0");
    }

    @Test
    void roadmapDocumentsV32Ga() throws Exception {
        assertThat(Files.readString(Path.of("ROADMAP.md")))
                .contains("3.2.0");
    }

    @Test
    void readmeDocumentsV32Ga() throws Exception {
        assertThat(Files.readString(Path.of("README.md")))
                .contains("3.2.0");
    }

    @Test
    void agentContextDocumentsPhase50() throws Exception {
        assertThat(Files.readString(Path.of(".codex",
                "AGENT_CONTEXT.md"))).contains("Phase 50");
    }

    @Test
    void adr268Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0268-v3.2-ga-freeze-and-product-completeness-baseline.md")
                .toFile()).exists();
    }

    @Test
    void gateV16DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "gate-convergence-v16.md").toFile()).exists();
    }

    @ParameterizedTest(name = "tag {0}")
    @MethodSource("releaseTags")
    void everyFrozenTagRegistered(String tag) throws Exception {
        assertThat(Files.readString(WORKFLOW)).contains(tag);
    }

    @ParameterizedTest(name = "benchmark class {0}")
    @MethodSource("benchmarkClasses")
    void benchmarkSuiteIncludes(String className) throws Exception {
        assertThat(Files.readString(WORKFLOW)).contains(className);
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
                java.util.stream.IntStream.rangeClosed(24, 50)
                        .mapToObj(phase -> "Phase"
                                + phase + "BenchmarkTest"),
                java.util.stream.IntStream.rangeClosed(43, 50)
                        .mapToObj(phase -> "Phase"
                                + phase
                                + "ProductionBaselineTest"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml() throws Exception {
        try (var reader = Files.newBufferedReader(WORKFLOW)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }
}
