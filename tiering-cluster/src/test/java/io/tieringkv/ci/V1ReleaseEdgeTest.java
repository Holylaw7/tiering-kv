package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v1 发布边缘（Phase 26 Goal 8）：版本标签、触发方式、脚本内容。 */
class V1ReleaseEdgeTest {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void dispatchTriggerPresent() throws Exception {
        Map<String, Object> doc = yaml();
        Object onValue = doc.get("on");
        if (onValue == null) {
            onValue = doc.get(Boolean.TRUE);
        }
        assertThat(onValue).isNotNull();
    }

    @Test
    void rcAndGaTagsIncluded() throws Exception {
        Map<String, Object> doc = yaml();
        Object onValue = doc.get("on");
        if (onValue == null) {
            onValue = doc.get(Boolean.TRUE);
        }
        Map<String, Object> on = (Map<String, Object>) onValue;
        Map<String, Object> push = (Map<String, Object>) on.get("push");
        List<String> tags = (List<String>) push.get("tags");
        assertThat(tags).anyMatch(tag -> tag.contains("v1.0.0-rc"));
        assertThat(tags).contains("v1.0.0");
    }

    @Test
    void releaseNotesScriptCoversPitrCdcSecurity() throws Exception {
        String content = Files.readString(Path.of("scripts",
                "release-notes.sh"));
        assertThat(content).contains("PITR").contains("CDC")
                .contains("Operator").contains("tierctl");
    }

    @Test
    void releaseNotesScriptVersionParameter() throws Exception {
        String content = Files.readString(Path.of("scripts",
                "release-notes.sh"));
        assertThat(content).contains("${1:-v1.0.0-rc1}");
    }

    @Test
    void trivyScanUsesFsMode() throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = (Map<String, Object>) doc.get("jobs");
        Map<String, Object> release =
                (Map<String, Object>) jobs.get("release");
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) release.get("steps");
        Map<String, Object> scan = steps.stream()
                .filter(step -> "Security scan".equals(step.get("name")))
                .findFirst().orElseThrow();
        Map<String, Object> with = (Map<String, Object>) scan.get("with");
        assertThat(with.get("scan-type")).isEqualTo("fs");
    }

    @Test
    void ghcrLoginBeforePush() throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = (Map<String, Object>) doc.get("jobs");
        Map<String, Object> release =
                (Map<String, Object>) jobs.get("release");
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) release.get("steps");
        Map<String, Object> publish = steps.stream()
                .filter(step -> "Publish image".equals(step.get("name")))
                .findFirst().orElseThrow();
        String run = (String) publish.get("run");
        assertThat(run).contains("docker login ghcr.io");
        assertThat(run).contains("docker push");
    }

    @Test
    void releaseNotesStepWritesFile() throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = (Map<String, Object>) doc.get("jobs");
        Map<String, Object> release =
                (Map<String, Object>) jobs.get("release");
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) release.get("steps");
        Map<String, Object> notes = steps.stream()
                .filter(step -> "Generate release notes"
                        .equals(step.get("name")))
                .findFirst().orElseThrow();
        String run = (String) notes.get("run");
        assertThat(run).contains("release-notes.md");
    }

    @Test
    void benchmarkStepIncludesAllPhaseSuites() throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = (Map<String, Object>) doc.get("jobs");
        Map<String, Object> release =
                (Map<String, Object>) jobs.get("release");
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) release.get("steps");
        Map<String, Object> benchmark = steps.stream()
                .filter(step -> "Benchmark".equals(step.get("name")))
                .findFirst().orElseThrow();
        String run = (String) benchmark.get("run");
        assertThat(run).contains("Phase24BenchmarkTest")
                .contains("Phase25BenchmarkTest")
                .contains("Phase26BenchmarkTest");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml() throws Exception {
        try (var reader = Files.newBufferedReader(WORKFLOW)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }
}
