package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v2.8.0 冻结与发布流水线（ADR-0233）。 */
class ReleaseV28Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v28TagRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.8.0-rc*");
        assertThat(content).contains("v2.8.0");
    }

    @Test
    void v27TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.7.0-rc*");
        assertThat(content).contains("v2.7.0");
    }

    @Test
    void benchmarkRunsPhase45Suite() throws Exception {
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
                .contains("Phase45BenchmarkTest");
    }

    @Test
    void v28ReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v2.8.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV28() throws Exception {
        String content = Files.readString(
                Path.of("CHANGELOG.md"));
        assertThat(content).contains("2.8.0");
    }

    @Test
    void roadmapDocumentsV28() throws Exception {
        String content = Files.readString(Path.of("ROADMAP.md"));
        assertThat(content).contains("2.8.0");
    }

    @Test
    void releaseNotesScriptCoversV28() throws Exception {
        String content = Files.readString(
                Path.of("scripts", "release-notes.sh"));
        assertThat(content).contains("v2.8.0-rc1");
    }

    @Test
    void adr233Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0233-v2.8-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void gateConvergenceV11DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "gate-convergence-v11.md").toFile()).exists();
    }

    @Test
    void tikvCrossMachineDocPresent() {
        assertThat(Path.of("docs", "benchmark",
                "tikv-cross-machine-baseline.md").toFile())
                .exists();
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
