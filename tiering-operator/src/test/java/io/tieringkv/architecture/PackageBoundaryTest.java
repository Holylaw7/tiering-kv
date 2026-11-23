package io.tieringkv.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包与模块边界固化（ADR-0346，TD-001 + P2-C2 多模块拆解）：
 * 扫描全部子模块 src/main/java 的 import，任何跨层反向依赖或核心镜像误引 fabric8 立即失败。
 */
class PackageBoundaryTest {

    private static final Pattern IMPORT =
            Pattern.compile("import io\\.tieringkv\\.(\\w+)\\.");
    private static final List<String> MODULES = List.of(
            "tiering-protocol",
            "tiering-storage",
            "tiering-server",
            "tiering-cluster",
            "tiering-operator");

    private static Map<String, Set<String>> dependencies()
            throws IOException {
        Map<String, Set<String>> deps = new HashMap<>();
        for (String module : MODULES) {
            Path root = Path.of(module, "src", "main", "java", "io", "tieringkv");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            Path rel = root.relativize(path);
                            String pkg = rel.getNameCount() <= 1
                                    ? "<root>" : rel.getName(0).toString();
                            deps.computeIfAbsent(pkg, ignored -> new HashSet<>());
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

    @Test
    void coreRuntimeModulesDoNotDependOnKubernetesOperator() throws Exception {
        for (String module : List.of(
                "tiering-protocol", "tiering-storage", "tiering-server", "tiering-cluster")) {
            Path srcRoot = Path.of(module, "src", "main", "java");
            assertThat(srcRoot.toFile()).exists();
            try (Stream<Path> files = Files.walk(srcRoot)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String text = Files.readString(p);
                        assertThat(text)
                                .as("module %s file %s must not import fabric8/operator", module, p)
                                .doesNotContain("import io.fabric8.")
                                .doesNotContain("import io.tieringkv.operator.")
                                .doesNotContain("import io.tieringkv.saas.");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
}
