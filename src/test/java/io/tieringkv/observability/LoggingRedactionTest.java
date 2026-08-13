package io.tieringkv.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构化日志与敏感信息脱敏（ADR-0263）。 */
class LoggingRedactionTest {

    @Test
    void nullAndBlankPassThrough() {
        assertThat(Redactor.mask(null)).isNull();
        assertThat(Redactor.mask("")).isEmpty();
        assertThat(Redactor.mask("   ")).isEqualTo("   ");
    }

    @Test
    void credentialAlwaysMasked() {
        assertThat(Redactor.maskCredential("super-secret"))
                .isEqualTo("***");
    }

    @Test
    void containsSecretDetectsLeak() {
        assertThat(Redactor.containsSecret("token=abc123", "abc123"))
                .isTrue();
        assertThat(Redactor.containsSecret("token=***", "abc123"))
                .isFalse();
    }

    @Test
    void startupLogSmoke() {
        OpsLogger.startup("node-1", "v3.2.0");
    }

    @Test
    void shutdownLogSmoke() {
        OpsLogger.shutdown("node-1");
    }

    @Test
    void walAndMigrationLogSmoke() {
        OpsLogger.walFlush(1000, 5);
        OpsLogger.migration(1000, 200_000);
    }

    @Test
    void raftAndTxnLogSmoke() {
        OpsLogger.raftElection("n1", 3, "n2");
        OpsLogger.txnCommit("txn-1", true);
    }

    @Test
    void credentialProbeLogSmoke() {
        OpsLogger.credentialProbe("s3", true);
        OpsLogger.warn("endpoint unavailable: {}", "s3");
    }

    @Test
    void logbackConfigExists() {
        assertThat(Path.of("src", "main", "resources",
                "logback.xml").toFile()).exists();
    }

    @Test
    void logbackHasRollingFileAppender() throws Exception {
        String config = Files.readString(Path.of("src", "main",
                "resources", "logback.xml"));
        assertThat(config).contains("RollingFileAppender");
        assertThat(config).contains("TimeBasedRollingPolicy");
        assertThat(config).contains("maxHistory");
    }

    @Test
    void logbackHasConsoleAndLevelConfig() throws Exception {
        String config = Files.readString(Path.of("src", "main",
                "resources", "logback.xml"));
        assertThat(config).contains("ConsoleAppender");
        assertThat(config).contains("LOG_LEVEL");
    }

    @ParameterizedTest(name = "mask {0}")
    @MethodSource("secretSamples")
    void secretsAreMasked(String input, String secret) {
        String masked = Redactor.mask(input);
        assertThat(masked).doesNotContain(secret);
        assertThat(masked).contains("***");
    }

    @ParameterizedTest(name = "logback token {0}")
    @MethodSource("logbackTokens")
    void logbackContainsToken(String token) throws Exception {
        String config = Files.readString(Path.of("src", "main",
                "resources", "logback.xml"));
        assertThat(config).contains(token);
    }

    @ParameterizedTest(name = "ops logger {0}")
    @MethodSource("loggerNames")
    void opsLoggerMethodsRun(String method) {
        switch (method) {
            case "startup" -> OpsLogger.startup("c", "v");
            case "shutdown" -> OpsLogger.shutdown("c");
            case "wal" -> OpsLogger.walFlush(1, 1);
            case "migration" -> OpsLogger.migration(1, 1);
            case "raft" -> OpsLogger.raftElection("n", 1, "l");
            case "txn" -> OpsLogger.txnCommit("t", true);
            case "credential" -> OpsLogger.credentialProbe("s", true);
            case "warn" -> OpsLogger.warn("message {}", "arg");
            default -> throw new AssertionError(method);
        }
    }

    static Stream<Arguments> secretSamples() {
        return Stream.of(
                Arguments.of("password=abc123", "abc123"),
                Arguments.of("passwd=abc123&user=x", "abc123"),
                Arguments.of("pwd=xyz789", "xyz789"),
                Arguments.of("secret=hunter2", "hunter2"),
                Arguments.of("token=eyJhbGciOiJIUzI1NiJ9", "eyJhbGciOiJIUzI1NiJ9"),
                Arguments.of("api_key=AKIAIOSFODNN7EXAMPLE", "AKIAIOSFODNN7EXAMPLE"),
                Arguments.of("api-key=abc-def-ghi", "abc-def-ghi"),
                Arguments.of("access_key=ABCDEF123456", "ABCDEF123456"),
                Arguments.of("credential=cred-1234", "cred-1234"),
                Arguments.of("Authorization: Bearer abc123", "Bearer abc123"),
                Arguments.of("Authorization=Bearer xyz789", "xyz789"),
                Arguments.of("jdbc:mysql://user:secret@host/db", "secret"),
                Arguments.of("https://user:pass@example.com/path", "pass"),
                Arguments.of("s3://key:secret@bucket", "secret"),
                Arguments.of("endpoint token=abc123 def", "abc123"),
                Arguments.of("password=one,password=two", "one"),
                Arguments.of("password=one,password=two", "two"),
                Arguments.of("key=AKIA12345678", "AKIA12345678"),
                Arguments.of("secret = spaced-secret", "spaced-secret"),
                Arguments.of("credential='quoted-secret'", "quoted-secret"),
                Arguments.of("token=\"double-secret\"", "double-secret"),
                Arguments.of("pwd=last-secret", "last-secret"),
                Arguments.of("auth_token=secret&token=other", "secret"),
                Arguments.of("password=alpha&password=beta", "beta"));
    }

    static Stream<Arguments> logbackTokens() {
        return Stream.of("LOG_DIR", "LOG_PATTERN", "CONSOLE", "FILE",
                        "RollingFileAppender", "maxHistory",
                        "%-5level", "%logger{40}", "%msg%n",
                        "LOG_LEVEL", "tiering-kv.log",
                        "TimeBasedRollingPolicy")
                .map(Arguments::of);
    }

    static Stream<Arguments> loggerNames() {
        return Stream.of("startup", "shutdown", "wal", "migration",
                        "raft", "txn", "credential", "warn")
                .map(Arguments::of);
    }
}
