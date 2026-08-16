package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 发布流水线（Phase 26 Goal 8）：release.yml 结构、顺序与版本标签。 */
class ReleaseWorkflowTest {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void workflowExistsAndParses() throws Exception {
        assertThat(Boolean.valueOf(Files.exists(WORKFLOW))).isTrue();
        try (var reader = Files.newBufferedReader(WORKFLOW)) {
            Object loaded = new Yaml().load(reader);
            assertThat(loaded).isNotNull();
        }
    }

    @Test
    void triggeredOnV1Tags() throws Exception {
        Map<String, Object> doc = yaml();
        Object onValue = doc.get("on");
        if (onValue == null) {
            onValue = doc.get(Boolean.TRUE); // YAML 1.1 布尔键
        }
        assertThat(onValue).isNotNull();
        Map<String, Object> on = map(onValue);
        Map<String, Object> push = map(on.get("push"));
        assertThat(push.get("tags")).isNotNull();
    }

    @Test
    void hasReleaseJob() throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = map(doc.get("jobs"));
        assertThat(jobs).containsKey("release");
    }

    @ParameterizedTest(name = "step {0}")
    @ValueSource(strings = {"Benchmark", "Security scan",
            "Build image", "Publish image", "Generate release notes"})
    void requiredStepsPresent(String stepName) throws Exception {
        List<Map<String, Object>> steps = releaseSteps();
        assertThat(steps).extracting(step -> step.get("name"))
                .contains(stepName);
    }

    @Test
    void testShardsGateReleaseBeforeBenchmarkBeforePublish()
            throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = map(doc.get("jobs"));
        assertThat(jobs).containsKey("test-shards");
        Map<String, Object> release = map(jobs.get("release"));
        assertThat(release.get("needs").toString())
                .contains("test-shards");
        List<Map<String, Object>> steps = releaseSteps();
        int benchIndex = indexOf(steps, "Benchmark");
        int publishIndex = indexOf(steps, "Publish image");
        assertThat(benchIndex).isLessThan(publishIndex);
    }

    @Test
    void benchmarkRunsPhase26Suite() throws Exception {
        Map<String, Object> benchmark = step("Benchmark");
        String run = (String) benchmark.get("run");
        assertThat(run).contains("Phase26BenchmarkTest");
    }

    @Test
    void securityScanEnabled() throws Exception {
        Map<String, Object> scan = step("Security scan");
        assertThat(scan).containsKey("uses");
        assertThat(scan.get("uses").toString())
                .contains("trivy");
    }

    @Test
    void imageTaggedWithRefName() throws Exception {
        Map<String, Object> build = step("Build image");
        String run = (String) build.get("run");
        assertThat(run).contains("GITHUB_REF_NAME");
        assertThat(run).contains("ghcr.io");
    }

    @Test
    void releaseNotesScriptExits() throws Exception {
        Path script = Path.of("scripts", "release-notes.sh");
        assertThat(Files.exists(script)).isTrue();
        String content = Files.readString(script);
        assertThat(content).contains("v1.0.0-rc1");
        assertThat(content).contains("PITR");
        assertThat(content).contains("CDC");
    }

    @Test
    void githubReleaseStepPublishes() throws Exception {
        List<Map<String, Object>> steps = releaseSteps();
        assertThat(steps).anyMatch(step ->
                step.get("uses") != null
                        && step.get("uses").toString()
                        .contains("action-gh-release"));
    }

    @Test
    void ghcrImageNameMatches() throws Exception {
        Map<String, Object> build = step("Build image");
        String run = (String) build.get("run");
        // GHCR 命名空间必须是 GitHub 用户/组织的全小写形式，
        // 与 release.yml 实际 push 的镜像名保持一致。
        assertThat(run).contains("ghcr.io/holylaw7/tiering-kv");
    }

    private static int indexOf(List<Map<String, Object>> steps,
                               String name) {
        for (int i = 0; i < steps.size(); i++) {
            if (name.equals(steps.get(i).get("name"))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> step(String name) throws Exception {
        return releaseSteps().stream()
                .filter(step -> name.equals(step.get("name")))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> releaseSteps()
            throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = map(doc.get("jobs"));
        Map<String, Object> release = map(jobs.get("release"));
        return (List<Map<String, Object>>) release.get("steps");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml() throws Exception {
        try (var reader = Files.newBufferedReader(WORKFLOW)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
