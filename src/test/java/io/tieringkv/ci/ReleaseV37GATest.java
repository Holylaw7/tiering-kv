package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** v3.7.0 GA 冻结（ADR-0304）。 */
class ReleaseV37GATest {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void gaTagsRegistered() throws Exception {
        assertThat(Files.readString(WORKFLOW))
                .contains("v3.7.0-rc*", "v3.7.0");
    }

    @Test
    void benchmarkRunsPhase56() throws Exception {
        assertThat(Files.readString(WORKFLOW))
                .contains("Phase56BenchmarkTest",
                        "Phase56ProductionBaselineTest");
    }

    @Test
    void gaReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.7.0-ga-release-notes.md").toFile()).exists();
    }

    @Test
    void releaseNotesScriptCoversGa() throws Exception {
        assertThat(Files.readString(Path.of("scripts",
                "release-notes.sh"))).contains("v3.7.0-ga");
    }

    @Test
    void versionCheckCoversGa() throws Exception {
        assertThat(Files.readString(Path.of("scripts",
                "version-check.sh"))).contains(
                "v3.7.0-ga-release-notes.md");
    }

    @ParameterizedTest(name = "tag {0}")
    @MethodSource("tags")
    void frozenTagsPresent(String tag) throws Exception {
        assertThat(Files.readString(WORKFLOW)).contains(tag);
    }

    static Stream<String> tags() {
        return Stream.of("v3.7.0-rc*", "v3.7.0", "v3.6.0-rc*",
                        "v3.6.0", "v3.5.0-rc*", "v3.5.0",
                        "v3.4.0-rc*", "v3.4.0", "v3.3.0-rc*",
                        "v3.3.0", "v3.2.0-rc*", "v3.2.0",
                        "v3.1.0-rc*", "v3.1.0", "v3.0.0-rc*",
                        "v3.0.0", "v2.9.0-rc*", "v2.9.0",
                        "v2.8.0-rc*", "v2.8.0", "v2.7.0-rc*",
                        "v2.7.0", "v2.6.0-rc*", "v2.6.0",
                        "v2.5.0-rc*", "v2.5.0", "v2.4.0-rc*",
                        "v2.4.0", "v2.3.0-rc*", "v2.3.0",
                        "v2.2.0-rc*", "v2.2.0", "v2.1.0-rc*",
                        "v2.1.0", "v2.0.0-rc*", "v2.0.0",
                        "v1.9.0-rc*", "v1.9.0", "v1.8.0-rc*",
                        "v1.8.0", "v1.7.0-rc*", "v1.7.0",
                        "v1.6.0-rc*", "v1.6.0", "v1.5.0-rc*",
                        "v1.5.0", "v1.4.0-rc*", "v1.4.0",
                        "v1.3.0-rc*", "v1.3.0", "v1.2.0-rc*",
                        "v1.2.0", "v1.1.0-rc*", "v1.1.0",
                        "v1.0.0-rc*", "v1.0.0");
    }
}
