package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v2.9.0 冻结与发布流水线（ADR-0240）。 */
class ReleaseV29Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v29TagRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.9.0-rc*");
        assertThat(content).contains("v2.9.0");
    }

    @Test
    void v28TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.8.0-rc*");
        assertThat(content).contains("v2.8.0");
    }

    @Test
    void benchmarkRunsPhase46Suite() throws Exception {
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
                .contains("Phase46BenchmarkTest");
    }

    @Test
    void v29ReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v2.9.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV29() throws Exception {
        String content = Files.readString(
                Path.of("CHANGELOG.md"));
        assertThat(content).contains("2.9.0");
    }

    @Test
    void roadmapDocumentsV29() throws Exception {
        String content = Files.readString(Path.of("ROADMAP.md"));
        assertThat(content).contains("2.9.0");
    }

    @Test
    void releaseNotesScriptCoversV29() throws Exception {
        String content = Files.readString(
                Path.of("scripts", "release-notes.sh"));
        assertThat(content).contains("v2.9.0-rc1");
    }

    @Test
    void adr240Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0240-v2.9-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void gateConvergenceV12DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "gate-convergence-v12.md").toFile()).exists();
    }

    @Test
    void tikvRegressionDocPresent() {
        assertThat(Path.of("docs", "benchmark",
                "tikv-cross-machine-regression.md").toFile())
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
