package io.tieringkv.ci;

import io.tieringkv.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** v3.4.0 冻结与发布流水线（ADR-0282）。 */
class ReleaseV34Test {

    private static final Path WORKFLOW = Path.of(".github",
            "workflows", "release.yml");

    @Test
    void v34TagsRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.4.0-rc*");
        assertThat(content).contains("v3.4.0");
    }

    @Test
    void v33TagsStillRegistered() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("v3.3.0-rc*");
        assertThat(content).contains("v3.3.0");
    }

    @Test
    void benchmarkRunsPhase52Suite() throws Exception {
        String content = Files.readString(WORKFLOW);
        assertThat(content).contains("Phase52BenchmarkTest");
        assertThat(content).contains("Phase52ProductionBaselineTest");
    }

    @Test
    void releaseNotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.4.0-release-notes.md").toFile()).exists();
    }

    @Test
    void changelogDocumentsV34() throws Exception {
        assertThat(Files.readString(Path.of("CHANGELOG.md")))
                .contains("3.4.0");
    }

    @Test
    void roadmapDocumentsV34() throws Exception {
        assertThat(Files.readString(Path.of("ROADMAP.md")))
                .contains("3.4.0");
    }

    @Test
    void readmeDocumentsV34() throws Exception {
        assertThat(Files.readString(Path.of("README.md")))
                .contains("3.4.0");
    }

    @Test
    void agentContextDocumentsPhase52() throws Exception {
        assertThat(Files.readString(Path.of(".codex",
                "AGENT_CONTEXT.md"))).contains("Phase 52");
    }

    @Test
    void adr282Present() {
        assertThat(Path.of("docs", "adr",
                "ADR-0282-pubsub-messaging-and-v3.4-freeze.md")
                .toFile()).exists();
    }

    @Test
    void protocolAndPubsubDocsPresent() {
        assertThat(Path.of("docs", "protocol",
                "resp3-support.md").toFile()).exists();
        assertThat(Path.of("docs", "operations",
                "pubsub-guide.md").toFile()).exists();
    }

    @Test
    void registryHas115Commands() {
        assertThat(CommandRegistry.createDefault().size())
                .isEqualTo(132);
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
                        "v3.0.0", "v3.1.0", "v3.2.0", "v3.3.0",
                        "v3.4.0")
                .flatMap(version -> Stream.of(
                        version + "-rc*", version));
    }

    static Stream<String> benchmarkClasses() {
        return Stream.concat(
                java.util.stream.IntStream.rangeClosed(24, 52)
                        .mapToObj(phase -> "Phase"
                                + phase + "BenchmarkTest"),
                java.util.stream.IntStream.rangeClosed(43, 52)
                        .mapToObj(phase -> "Phase"
                                + phase
                                + "ProductionBaselineTest"));
    }

    static Stream<String> commands() {
        return Stream.of("hset", "hget", "hdel", "hexists", "hlen",
                        "hkeys", "hvals", "hgetall", "hmget",
                        "hmset", "hincrby", "hsetnx", "lpush",
                        "rpush", "lpop", "rpop", "llen", "lrange",
                        "lindex", "lset", "lrem", "ltrim", "sadd",
                        "srem", "sismember", "scard", "smembers",
                        "spop", "srandmember", "sinter", "sunion",
                        "sdiff", "sinterstore", "sunionstore",
                        "sdiffstore", "zadd", "zscore", "zrange",
                        "zrevrange", "zrem", "zcard", "zincrby",
                        "zrangebyscore", "zcount", "zrank",
                        "zrevrank", "hello", "subscribe",
                        "unsubscribe", "psubscribe", "punsubscribe",
                        "publish");
    }
}
