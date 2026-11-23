package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.3 发布（Goal 6）：标签与基准套件。 */
class ReleaseV13Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v13TagsTriggered() throws Exception {
        Map<String, Object> doc = yaml();
        Object onValue = doc.get("on");
        if (onValue == null) {
            onValue = doc.get(Boolean.TRUE);
        }
        Map<String, Object> on = (Map<String, Object>) onValue;
        Map<String, Object> push = (Map<String, Object>) on.get("push");
        List<String> tags = (List<String>) push.get("tags");
        assertThat(tags).contains("v1.3.0").contains("v1.3.0-rc*");
    }

    @Test
    void benchmarkIncludesPhase30() throws Exception {
        Map<String, Object> doc = yaml();
        Map<String, Object> jobs = (Map<String, Object>) doc.get("jobs");
        Map<String, Object> release =
                (Map<String, Object>) jobs.get("release");
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) release.get("steps");
        String run = steps.stream()
                .filter(step -> "Benchmark".equals(step.get("name")))
                .map(step -> (String) step.get("run"))
                .findFirst().orElse("");
        assertThat(run).contains("Phase30BenchmarkTest");
    }

    @Test
    void v12TagsStillPresent() throws Exception {
        Map<String, Object> doc = yaml();
        Object onValue = doc.get("on");
        if (onValue == null) {
            onValue = doc.get(Boolean.TRUE);
        }
        Map<String, Object> on = (Map<String, Object>) onValue;
        Map<String, Object> push = (Map<String, Object>) on.get("push");
        assertThat((List<String>) push.get("tags")).contains("v1.2.0");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml() throws Exception {
        try (var reader = Files.newBufferedReader(WORKFLOW)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }
}
