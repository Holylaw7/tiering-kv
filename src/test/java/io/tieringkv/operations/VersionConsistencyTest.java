package io.tieringkv.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 版本模型与制品一致性（ADR-0262）。 */
class VersionConsistencyTest {

    @Test
    void pomHasRevisionProperty() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("<revision>");
        assertThat(pom).contains("4.1.0");
    }

    @Test
    void pomVersionUsesRevision() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("<version>${revision}</version>");
    }

    @Test
    void flattenPluginPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("flatten-maven-plugin");
    }

    @Test
    void jacocoPluginPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("jacoco-maven-plugin");
    }

    @Test
    void spotbugsPluginPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("spotbugs-maven-plugin");
    }

    @Test
    void dependencyPluginPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("maven-dependency-plugin");
    }

    @Test
    void loggingDependenciesPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("slf4j-api");
        assertThat(pom).contains("logback-classic");
    }

    @Test
    void jmhDependenciesPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("jmh-core");
        assertThat(pom).contains("jmh-generator-annprocess");
    }

    @Test
    void jmhPluginPresent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("jmh-maven-plugin");
    }

    @Test
    void engineeringScriptsExist() {
        assertThat(Path.of("scripts", "version-check.sh").toFile())
                .exists();
        assertThat(Path.of("scripts", "coverage-check.sh").toFile())
                .exists();
        assertThat(Path.of("scripts", "quality-gates.sh").toFile())
                .exists();
        assertThat(Path.of("scripts", "benchmark-jmh.sh").toFile())
                .exists();
    }

    @ParameterizedTest(name = "{0} contains {1}")
    @MethodSource("versionArtifacts")
    void versionArtifactsConsistent(String file, String version)
            throws Exception {
        assertThat(Files.readString(Path.of(file)))
                .contains(version);
    }

    @ParameterizedTest(name = "{0} documents {1}")
    @MethodSource("priorVersions")
    void priorVersionsStillDocumented(String file, String version)
            throws Exception {
        assertThat(Files.readString(Path.of(file)))
                .contains(version);
    }

    @ParameterizedTest(name = "script token {0} in {1}")
    @MethodSource("scriptTokens")
    void engineeringScriptsCarryRequiredTokens(String file,
                                               String token)
            throws Exception {
        assertThat(Files.readString(Path.of("scripts", file)))
                .contains(token);
    }

    static Stream<Arguments> versionArtifacts() {
        return Stream.of(
                Arguments.of("CHANGELOG.md", "3.2.0"),
                Arguments.of("ROADMAP.md", "3.2.0"),
                Arguments.of("docs/project-history.md", "3.2.0"),
                Arguments.of("docs/release/v3.2.0-ga-release-notes.md",
                        "v3.2.0"),
                Arguments.of("scripts/release-notes.sh",
                        "v3.2.0-rc1"),
                Arguments.of("docs/operations/versioning-and-artifacts.md",
                        "3.7.0"),
                Arguments.of("docs/deployment/ci-execution-and-release-v3.2.md",
                        "v3.2.0"),
                Arguments.of("docs/review/product-completeness-baseline.md",
                        "3.2.0"));
    }

    static Stream<Arguments> priorVersions() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (String file : new String[]{"CHANGELOG.md",
                "ROADMAP.md", "docs/project-history.md"}) {
            for (String version : new String[]{"v2.9.0", "v3.0.0",
                    "v3.1.0"}) {
                builder.add(Arguments.of(file, version));
            }
        }
        return builder.build();
    }

    static Stream<Arguments> scriptTokens() {
        return Stream.of(
                Arguments.of("version-check.sh", "pom.xml"),
                Arguments.of("version-check.sh", "grep"),
                Arguments.of("version-check.sh", "exit 1"),
                Arguments.of("version-check.sh", "CHANGELOG.md"),
                Arguments.of("coverage-check.sh", "jacoco.csv"),
                Arguments.of("coverage-check.sh", "COVERAGE_THRESHOLD"),
                Arguments.of("coverage-check.sh", "awk"),
                Arguments.of("coverage-check.sh", "exit 1"),
                Arguments.of("quality-gates.sh", "jacoco:report"),
                Arguments.of("quality-gates.sh", "spotbugs"),
                Arguments.of("quality-gates.sh", "dependency:analyze"),
                Arguments.of("quality-gates.sh", "coverage-check.sh"),
                Arguments.of("benchmark-jmh.sh", "jmh:benchmark"),
                Arguments.of("benchmark-jmh.sh", "includes"),
                Arguments.of("benchmark-jmh.sh", "test-compile"));
    }
}
