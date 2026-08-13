package io.tieringkv.ci;

import io.tieringkv.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** v3.7.0 冻结与发布流水线（ADR-0303）。 */
class ReleaseV37Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v37TagsRegistered() throws Exception {
        assertThat(Files.readString(WORKFLOW))
                .contains("v3.7.0-rc*", "v3.7.0");
    }

    @Test
    void v36TagsStillRegistered() throws Exception {
        assertThat(Files.readString(WORKFLOW))
                .contains("v3.6.0-rc*", "v3.6.0");
    }

    @Test
    void benchmarkRunsPhase55Suite() throws Exception {
        assertThat(Files.readString(WORKFLOW))
                .contains("Phase55BenchmarkTest",
                        "Phase55ProductionBaselineTest");
    }

    @Test
    void releaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.7.0-release-notes.md").toFile()).exists();
    }

    @Test
    void docsSync() throws Exception {
        assertThat(Files.readString(Path.of("CHANGELOG.md")))
                .contains("3.7.0");
        assertThat(Files.readString(Path.of("ROADMAP.md")))
                .contains("3.7.0");
        assertThat(Files.readString(Path.of("README.md")))
                .contains("3.7.0");
        assertThat(Files.readString(Path.of(".codex",
                "AGENT_CONTEXT.md"))).contains("Phase 55");
    }

    @Test
    void adr303Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0303-v3.7-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void registryHas115Commands() {
        assertThat(CommandRegistry.createDefault().size())
                .isEqualTo(115);
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

    @ParameterizedTest(name = "command {0}")
    @MethodSource("commands")
    void everyCommandRegistered(String command) {
        assertThat(CommandRegistry.createDefault().find(command))
                .isNotNull();
    }

    static Stream<String> releaseTags() {
        return Stream.of("v1.0.0", "v1.1.0", "v1.2.0", "v1.3.0",
                        "v1.4.0", "v1.5.0", "v1.6.0", "v1.7.0",
                        "v1.8.0", "v1.9.0", "v2.0.0", "v2.1.0",
                        "v2.2.0", "v2.3.0", "v2.4.0", "v2.5.0",
                        "v2.6.0", "v2.7.0", "v2.8.0", "v2.9.0",
                        "v3.0.0", "v3.1.0", "v3.2.0", "v3.3.0",
                        "v3.4.0", "v3.5.0", "v3.6.0", "v3.7.0")
                .flatMap(version -> Stream.of(
                        version + "-rc*", version));
    }

    static Stream<String> benchmarkClasses() {
        return Stream.concat(
                java.util.stream.IntStream.rangeClosed(24, 55)
                        .mapToObj(phase -> "Phase"
                                + phase + "BenchmarkTest"),
                java.util.stream.IntStream.rangeClosed(43, 55)
                        .mapToObj(phase -> "Phase"
                                + phase
                                + "ProductionBaselineTest"));
    }

    static Stream<String> commands() {
        return Stream.of("xgroup", "xreadgroup", "xack",
                        "xpending");
    }
}
