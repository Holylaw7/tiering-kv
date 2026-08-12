package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v2.7.0 冻结与发布流水线（ADR-0226）。 */
class ReleaseV27Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v27TagRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.7.0-rc*");
        assertThat(content).contains("v2.7.0");
    }

    @Test
    void v26TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.6.0-rc*");
        assertThat(content).contains("v2.6.0");
    }

    @Test
    void benchmarkRunsPhase44Suite() throws Exception {
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
                .contains("Phase44BenchmarkTest");
    }

    @Test
    void v27ReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v2.7.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV27() throws Exception {
        String content = Files.readString(
                Path.of("CHANGELOG.md"));
        assertThat(content).contains("2.7.0");
    }

    @Test
    void roadmapDocumentsV27() throws Exception {
        String content = Files.readString(Path.of("ROADMAP.md"));
        assertThat(content).contains("2.7.0");
    }

    @Test
    void releaseNotesScriptCoversV27() throws Exception {
        String content = Files.readString(
                Path.of("scripts", "release-notes.sh"));
        assertThat(content).contains("v2.7.0-rc1");
    }

    @Test
    void adr226Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0226-v2.7-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void gateConvergenceV10DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "gate-convergence-v10.md").toFile()).exists();
    }

    @Test
    void tikvComparisonDocPresent() {
        assertThat(Path.of("docs", "benchmark",
                "tikv-comparison-baseline.md").toFile()).exists();
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
