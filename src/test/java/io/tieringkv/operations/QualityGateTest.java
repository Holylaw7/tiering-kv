package io.tieringkv.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 质量门禁（ADR-0264）：覆盖率/静态分析/依赖审计配置可运行。 */
class QualityGateTest {

    @Test
    void jacocoConfiguredInPom() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("jacoco-maven-plugin");
        assertThat(pom).contains("prepare-agent");
        assertThat(pom).contains("<phase>test</phase>");
    }

    @Test
    void spotbugsConfiguredInPom() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("spotbugs-maven-plugin");
        assertThat(pom).contains("failOnError");
    }

    @Test
    void dependencyAnalyzeConfiguredInPom() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("maven-dependency-plugin");
    }

    @Test
    void coverageCheckScriptExists() {
        assertThat(Path.of("scripts", "coverage-check.sh").toFile())
                .exists();
    }

    @Test
    void qualityGatesScriptExists() {
        assertThat(Path.of("scripts", "quality-gates.sh").toFile())
                .exists();
    }

    @Test
    void coverageScriptParsesJacocoCsv() throws Exception {
        String script = Files.readString(Path.of("scripts",
                "coverage-check.sh"));
        assertThat(script).contains("jacoco.csv");
        assertThat(script).contains("awk");
    }

    @Test
    void qualityScriptRunsAllGates() throws Exception {
        String script = Files.readString(Path.of("scripts",
                "quality-gates.sh"));
        assertThat(script).contains("jacoco:report");
        assertThat(script).contains("spotbugs:spotbugs");
        assertThat(script).contains("dependency:analyze");
    }

    @Test
    void surefireKeepsJacocoAgent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("@{argLine}");
        assertThat(pom).contains("-Xmx1g");
    }

    @ParameterizedTest(name = "plugin {0}")
    @MethodSource("pluginArtifacts")
    void qualityPluginsPresent(String artifactId) throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains(artifactId);
    }

    @ParameterizedTest(name = "script {0} token {1}")
    @MethodSource("scriptTokens")
    void qualityScriptsCarryTokens(String script, String token)
            throws Exception {
        assertThat(Files.readString(Path.of("scripts", script)))
                .contains(token);
    }

    @ParameterizedTest(name = "coverage {0}")
    @MethodSource("coverageTokens")
    void coverageScriptCarriesThresholdLogic(String token)
            throws Exception {
        String script = Files.readString(Path.of("scripts",
                "coverage-check.sh"));
        assertThat(script).contains(token);
    }

    static Stream<Arguments> pluginArtifacts() {
        return Stream.of("jacoco-maven-plugin", "spotbugs-maven-plugin",
                        "maven-dependency-plugin", "jmh-maven-plugin",
                        "flatten-maven-plugin", "maven-surefire-plugin",
                        "maven-compiler-plugin", "maven-shade-plugin",
                        "maven-jar-plugin", "exec-maven-plugin")
                .map(Arguments::of);
    }

    static Stream<Arguments> scriptTokens() {
        return Stream.of(
                Arguments.of("coverage-check.sh", "set -euo pipefail"),
                Arguments.of("coverage-check.sh", "COVERAGE_THRESHOLD"),
                Arguments.of("coverage-check.sh", "exit 1"),
                Arguments.of("coverage-check.sh", "target/site/jacoco"),
                Arguments.of("quality-gates.sh", "set -euo pipefail"),
                Arguments.of("quality-gates.sh", "mvn -q"),
                Arguments.of("quality-gates.sh", "|| true"),
                Arguments.of("quality-gates.sh", "target/"),
                Arguments.of("benchmark-jmh.sh", "set -euo pipefail"),
                Arguments.of("benchmark-jmh.sh", "INCLUDES"),
                Arguments.of("benchmark-jmh.sh", "MemTableGetBenchmark"),
                Arguments.of("benchmark-jmh.sh", "WalAppendBenchmark"),
                Arguments.of("benchmark-jmh.sh", "SstableRandomReadBenchmark"),
                Arguments.of("version-check.sh", "set -euo pipefail"),
                Arguments.of("version-check.sh", "REVISION"),
                Arguments.of("version-check.sh", "MAJOR_MINOR_PATCH"),
                Arguments.of("version-check.sh", "version-check: OK"),
                Arguments.of("coverage-check.sh", "coverage-check: OK"),
                Arguments.of("coverage-check.sh", "printf"),
                Arguments.of("quality-gates.sh", "echo"),
                Arguments.of("quality-gates.sh", "coverage-check"),
                Arguments.of("benchmark-jmh.sh", "echo"),
                Arguments.of("version-check.sh", "for file"),
                Arguments.of("version-check.sh", "grep -q"),
                Arguments.of("coverage-check.sh", "lm + lc"),
                Arguments.of("coverage-check.sh", "pct"),
                Arguments.of("quality-gates.sh", "report"),
                Arguments.of("quality-gates.sh", "analyze"),
                Arguments.of("benchmark-jmh.sh", "target/jmh-results"),
                Arguments.of("version-check.sh", "head -n 1"));
    }

    static Stream<Arguments> coverageTokens() {
        return Stream.of("THRESHOLD", "jacoco.csv", "awk", "exit 1",
                        "line coverage", "%.2f%%", "ENVIRON",
                        "no instructions", "missing", "run mvn test")
                .map(Arguments::of);
    }
}
