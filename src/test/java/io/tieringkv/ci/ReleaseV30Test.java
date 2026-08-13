package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v3.0.0 GA 冻结与发布流水线（ADR-0247）。 */
class ReleaseV30Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v30TagRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.0.0-rc*");
        assertThat(content).contains("v3.0.0");
    }

    @Test
    void v29TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v2.9.0-rc*");
        assertThat(content).contains("v2.9.0");
    }

    @Test
    void benchmarkRunsPhase47Suite() throws Exception {
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
                .contains("Phase47BenchmarkTest");
    }

    @Test
    void v30ReleaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.0.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV30() throws Exception {
        String content = Files.readString(
                Path.of("CHANGELOG.md"));
        assertThat(content).contains("3.0.0");
    }

    @Test
    void roadmapDocumentsV30() throws Exception {
        String content = Files.readString(Path.of("ROADMAP.md"));
        assertThat(content).contains("3.0.0");
    }

    @Test
    void releaseNotesScriptCoversV30() throws Exception {
        String content = Files.readString(
                Path.of("scripts", "release-notes.sh"));
        assertThat(content).contains("v3.0.0-rc1");
    }

    @Test
    void adr247Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0247-v3.0-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void gateConvergenceV13DocPresent() {
        assertThat(Path.of("docs", "deployment",
                "gate-convergence-v13.md").toFile()).exists();
    }

    @Test
    void tikvAlertingDocPresent() {
        assertThat(Path.of("docs", "benchmark",
                "tikv-cross-machine-regression-alerting.md")
                .toFile()).exists();
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
