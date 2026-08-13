package io.tieringkv.benchmark.jmh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** JMH 基准工程化骨架（ADR-0267）。 */
class JmhSkeletonTest {

    private static final Path JMH_DIR = Path.of("src", "test",
            "java", "io", "tieringkv", "benchmark", "jmh");

    @Test
    void threeCoreBenchmarksExist() {
        assertThat(JMH_DIR.resolve("MemTableGetBenchmark.java")
                .toFile()).exists();
        assertThat(JMH_DIR.resolve("WalAppendBenchmark.java")
                .toFile()).exists();
        assertThat(JMH_DIR.resolve(
                "SstableRandomReadBenchmark.java").toFile())
                .exists();
    }

    @Test
    void pomHasJmhCore() throws Exception {
        assertThat(Files.readString(Path.of("pom.xml")))
                .contains("jmh-core");
    }

    @Test
    void pomHasJmhAnnotationProcessor() throws Exception {
        assertThat(Files.readString(Path.of("pom.xml")))
                .contains("jmh-generator-annprocess");
    }

    @Test
    void pomHasJmhPlugin() throws Exception {
        assertThat(Files.readString(Path.of("pom.xml")))
                .contains("jmh-maven-plugin");
    }

    @Test
    void benchmarkScriptExists() {
        assertThat(Path.of("scripts", "benchmark-jmh.sh").toFile())
                .exists();
    }

    @Test
    void benchmarkReportDocExists() {
        assertThat(Path.of("docs", "benchmark",
                "jmh-core-report.md").toFile()).exists();
    }

    @Test
    void scriptRunsAllThreeBenchmarks() throws Exception {
        String script = Files.readString(Path.of("scripts",
                "benchmark-jmh.sh"));
        assertThat(script).contains("MemTableGetBenchmark");
        assertThat(script).contains("WalAppendBenchmark");
        assertThat(script).contains("SstableRandomReadBenchmark");
    }

    @Test
    void scriptFixesParameters() throws Exception {
        String script = Files.readString(Path.of("scripts",
                "benchmark-jmh.sh"));
        assertThat(script).contains("test-compile");
        assertThat(script).contains("jmh:benchmark");
    }

    @ParameterizedTest(name = "benchmark {0} has benchmark method {1}")
    @MethodSource("benchmarkMethods")
    void benchmarkClassHasBenchmarkMethod(String className,
                                          String method) {
        assertThat(readBenchmark(className)).contains("@Benchmark");
        assertThat(readBenchmark(className)).contains(method);
    }

    @ParameterizedTest(name = "benchmark {0} has annotation {1}")
    @MethodSource("benchmarkAnnotations")
    void benchmarkClassHasAnnotation(String className,
                                     String annotation) {
        assertThat(readBenchmark(className)).contains(annotation);
    }

    @ParameterizedTest(name = "pom token {0}")
    @MethodSource("pomTokens")
    void pomCarriesJmhTokens(String token) throws Exception {
        assertThat(Files.readString(Path.of("pom.xml")))
                .contains(token);
    }

    @ParameterizedTest(name = "script token {0}")
    @MethodSource("scriptTokens")
    void scriptCarriesTokens(String token) throws Exception {
        assertThat(Files.readString(Path.of("scripts",
                "benchmark-jmh.sh"))).contains(token);
    }

    private static String readBenchmark(String className) {
        try {
            return Files.readString(
                    JMH_DIR.resolve(className + ".java"));
        } catch (Exception e) {
            throw new AssertionError("cannot read " + className, e);
        }
    }

    static Stream<Arguments> benchmarkMethods() {
        return Stream.of(
                Arguments.of("MemTableGetBenchmark", "byte[] get()"),
                Arguments.of("WalAppendBenchmark", "void append()"),
                Arguments.of("SstableRandomReadBenchmark",
                        "randomRead"));
    }

    static Stream<Arguments> benchmarkAnnotations() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (String className : new String[]{
                "MemTableGetBenchmark", "WalAppendBenchmark",
                "SstableRandomReadBenchmark"}) {
            for (String annotation : new String[]{"@State",
                    "@Benchmark", "@Fork", "@Warmup",
                    "@Measurement", "@Setup"}) {
                builder.add(Arguments.of(className, annotation));
            }
        }
        return builder.build();
    }

    static Stream<Arguments> pomTokens() {
        return Stream.of("jmh-core", "jmh-generator-annprocess",
                        "jmh-maven-plugin", "org.openjdk.jmh",
                        "1.37", "<scope>test</scope>")
                .map(Arguments::of);
    }

    static Stream<Arguments> scriptTokens() {
        return Stream.of("INCLUDES", "test-compile", "jmh:benchmark",
                        "MemTableGetBenchmark", "WalAppendBenchmark",
                        "SstableRandomReadBenchmark",
                        "target/jmh-results", "echo")
                .map(Arguments::of);
    }
}
