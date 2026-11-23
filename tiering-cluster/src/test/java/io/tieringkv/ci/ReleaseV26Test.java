package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v2.6.0 冻结与发布流水线（ADR-0219）。 */
class ReleaseV26Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v26TagRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.6.0-rc*");
        assertThat(content).contains("v2.6.0");
    }

    @Test
    void v25TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.5.0-rc*");
        assertThat(content).contains("v2.5.0");
    }

    @Test
    void benchmarkRunsPhase43Suite() throws Exception {
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
                .contains("Phase43BenchmarkTest");
    }

    @Test
    void v26ReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v2.6.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV26() throws Exception {
        String content = Files.readString(
                Path.of("CHANGELOG.md"));
        assertThat(content).contains("2.6.0");
    }

    @Test
    void roadmapDocumentsV26() throws Exception {
        String content = Files.readString(Path.of("ROADMAP.md"));
        assertThat(content).contains("2.6.0");
    }

    @Test
    void releaseNotesScriptCoversV26() throws Exception {
        String content = Files.readString(
                Path.of("scripts", "release-notes.sh"));
        assertThat(content).contains("v2.6.0-rc1");
    }

    @Test
    void adr219Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0219-v2.6-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void gateConvergenceV9DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "gate-convergence-v9.md").toFile()).exists();
    }

    @Test
    void productionBaselineDocPresent() {
        assertThat(Path.of("docs", "benchmark",
                "production-baseline.md").toFile()).exists();
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
