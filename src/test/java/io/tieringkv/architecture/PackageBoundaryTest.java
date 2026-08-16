package io.tieringkv.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包边界固化（ADR-0346，TD-001）：保持单模块，但关键分层必须单向。
 * 扫描 src/main/java 的 import，任何跨层反向依赖立即失败。
 */
class PackageBoundaryTest {

    private static final Pattern IMPORT =
            Pattern.compile("import io\\.tieringkv\\.(\\w+)\\.");

    private static Map<String, Set<String>> dependencies()
            throws IOException {
        Map<String, Set<String>> deps = new HashMap<>();
        Path root = Path.of("src/main/java/io/tieringkv");
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        String pkg = root.relativize(path)
                                .getName(0).toString();
                        deps.computeIfAbsent(pkg,
                                ignored -> new HashSet<>());
                        try (Stream<String> lines = Files.lines(path)) {
                            lines.forEach(line -> {
                                Matcher m = IMPORT.matcher(line);
                                if (m.find() && !m.group(1).equals(pkg)) {
                                    deps.get(pkg).add(m.group(1));
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return Collections.unmodifiableMap(deps);
    }

    private static Set<String> deps(Map<String, Set<String>> graph,
                                    String pkg) {
        return graph.getOrDefault(pkg, Set.of());
    }

    @Test
    void protocolHasNoInternalDependencies() throws Exception {
        assertThat(deps(dependencies(), "protocol")).isEmpty();
    }

    @Test
    void storageDoesNotDependOnCommandOrNetwork() throws Exception {
        Set<String> storage = deps(dependencies(), "storage");
        assertThat(storage).doesNotContain("command", "network");
    }

    @Test
    void commandDoesNotDependOnNetwork() throws Exception {
        assertThat(deps(dependencies(), "command"))
                .doesNotContain("network");
    }

    @Test
    void mainChainIsAcyclicAndUnidirectional() throws Exception {
        Map<String, Set<String>> graph = dependencies();
        assertThat(deps(graph, "protocol")).isEmpty();
        assertThat(deps(graph, "storage"))
                .doesNotContain("command", "network");
        assertThat(deps(graph, "command"))
                .doesNotContain("network");
        // network 允许依赖 command/protocol/storage，但不得被下层依赖
        assertThat(deps(graph, "network"))
                .containsAnyOf("command", "protocol", "storage");
        assertThat(deps(graph, "command")).doesNotContain("network");
    }
}
