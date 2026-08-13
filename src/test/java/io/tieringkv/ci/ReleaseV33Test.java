package io.tieringkv.ci;

import io.tieringkv.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** v3.3.0 冻结与发布流水线（ADR-0275）。 */
class ReleaseV33Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v33TagsRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.3.0-rc*");
        assertThat(content).contains("v3.3.0");
    }

    @Test
    void v32TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.2.0-rc*");
        assertThat(content).contains("v3.2.0");
    }

    @Test
    void benchmarkRunsPhase51Suite() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("Phase51BenchmarkTest");
        assertThat(content).contains("Phase51ProductionBaselineTest");
    }

    @Test
    void releaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.3.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV33() throws Exception {
        assertThat(Files.readString(Path.of("CHANGELOG.md")))
                .contains("3.3.0");
    }

    @Test
    void roadmapDocumentsV33() throws Exception {
        assertThat(Files.readString(Path.of("ROADMAP.md")))
                .contains("3.3.0");
    }

    @Test
    void readmeDocumentsV33() throws Exception {
        assertThat(Files.readString(Path.of("README.md")))
                .contains("3.3.0");
    }

    @Test
    void agentContextDocumentsPhase51() throws Exception {
        assertThat(Files.readString(Path.of(".codex",
                "AGENT_CONTEXT.md"))).contains("Phase 51");
    }

    @Test
    void adr275Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0275-v3.3-freeze-and-release-pipeline.md")
                .toFile()).exists();
    }

    @Test
    void commandDocsPresent() {
        assertThat(Path.of("docs", "design",
                "command-family-design.md").toFile()).exists();
        assertThat(Path.of("docs", "protocol",
                "resp2-compatibility-matrix.md").toFile()).exists();
    }

    @Test
    void registryHas109Commands() {
        assertThat(CommandRegistry.createDefault().size())
                .isEqualTo(109);
    }

    @ParameterizedTest(name = "tag {0}")
    @MethodSource("releaseTags")
    void everyFrozenTagRegistered(String tag) throws Exception {
        assertThat(Files.readString(WORKFLOW)).contains(tag);
    }

    @ParameterizedTest(name = "benchmark class {0}")
    @MethodSource("benchmarkClasses")
    void benchmarkSuiteIncludes(String className) throws Exception {
        assertThat(Files.readString(WORKFLOW)).contains(className);
    }

    @ParameterizedTest(name = "command {0}")
    @MethodSource("commands")
    void everyCommandRegistered(String command) {
        assertThat(CommandRegistry.createDefault().find(command))
                .isNotNull();
    }

    static Stream<String> releaseTags() {
        return Stream.of("v1.0.0", "v1.1.0", "v1.2.0", "v1.3.0",
                        "v1.4.0", "v1.5.0", "v1.6.0", "v1.7.0",
                        "v1.8.0", "v1.9.0", "v2.0.0", "v2.1.0",
                        "v2.2.0", "v2.3.0", "v2.4.0", "v2.5.0",
                        "v2.6.0", "v2.7.0", "v2.8.0", "v2.9.0",
                        "v3.0.0", "v3.1.0", "v3.2.0", "v3.3.0")
                .flatMap(version -> Stream.of(
                        version + "-rc*", version));
    }

    static Stream<String> benchmarkClasses() {
        return Stream.concat(
                java.util.stream.IntStream.rangeClosed(24, 51)
                        .mapToObj(phase -> "Phase"
                                + phase + "BenchmarkTest"),
                java.util.stream.IntStream.rangeClosed(43, 51)
                        .mapToObj(phase -> "Phase"
                                + phase
                                + "ProductionBaselineTest"));
    }

    static Stream<String> commands() {
        return Stream.of("ping", "echo", "set", "get", "del",
                        "exists", "info", "incr", "decr", "incrby",
                        "decrby", "append", "strlen", "getset",
                        "setnx", "setex", "psetex", "getdel",
                        "getrange", "setrange", "ttl", "pttl",
                        "expire", "pexpire", "expireat", "pexpireat",
                        "persist", "mget", "mset", "msetnx",
                        "dbsize", "flushdb", "flushall", "scan",
                        "type", "config", "client", "command");
    }
}
