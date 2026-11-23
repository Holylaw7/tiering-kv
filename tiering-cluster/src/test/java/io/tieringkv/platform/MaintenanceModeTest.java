package io.tieringkv.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 57 维护模式矩阵（ADR-0311~0317）。 */
class MaintenanceModeTest {

    @Test
    void maintenanceScriptsExist() {
        for (String script : new String[]{"hotfix.sh",
                "runner-review.sh", "annual-review.sh",
                "maintenance-gates.sh", "sbom.sh"}) {
            assertThat(Path.of("scripts", script).toFile())
                    .exists();
        }
    }

    @Test
    void planningDocsExist() {
        assertThat(Path.of("docs", "planning",
                "v4-roadmap.md").toFile()).exists();
        assertThat(Path.of("docs", "planning",
                "rfc-template.md").toFile()).exists();
    }

    @Test
    void securityPolicyExists() {
        assertThat(Path.of("docs", "security",
                "disclosure-policy.md").toFile()).exists();
    }

    @Test
    void v371TagsRegistered() throws Exception {
        assertThat(Files.readString(Path.of(".github",
                "workflows", "release.yml")))
                .contains("v3.7.1-rc*", "v3.7.1");
    }

    @Test
    void v371NotesExist() {
        assertThat(Path.of("docs", "release",
                "v3.7.1-rc-maintenance-notes.md").toFile())
                .exists();
    }

    @ParameterizedTest(name = "script {0} token {1}")
    @MethodSource("scriptTokens")
    void scriptsCarryTokens(String script, String token)
            throws Exception {
        assertThat(Files.readString(Path.of("scripts", script)))
                .contains(token);
    }

    @ParameterizedTest(name = "doc {0} token {1}")
    @MethodSource("docTokens")
    void docsCarryTokens(String doc, String token)
            throws Exception {
        assertThat(Files.readString(Path.of(doc)))
                .contains(token);
    }

    @ParameterizedTest(name = "adr {0}")
    @MethodSource("adrs")
    void adrsPresent(String fileName) {
        assertThat(Path.of("docs", "adr", fileName).toFile())
                .exists();
    }

    @ParameterizedTest(name = "maintenance doc {0}")
    @MethodSource("maintenanceDocs")
    void maintenanceDocsPresent(String path) {
        assertThat(Path.of(path).toFile()).exists();
    }

    static Stream<Arguments> scriptTokens() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (String script : new String[]{"hotfix.sh",
                "runner-review.sh", "annual-review.sh",
                "maintenance-gates.sh", "sbom.sh"}) {
            builder.add(Arguments.of(script,
                    "#!/usr/bin/env bash"));
            builder.add(Arguments.of(script, "set -euo pipefail"));
        }
        builder.add(Arguments.of("hotfix.sh", "fix/"));
        builder.add(Arguments.of("hotfix.sh", "mvn -q test"));
        builder.add(Arguments.of("runner-review.sh",
                "TD-048"));
        builder.add(Arguments.of("runner-review.sh",
                "evidence"));
        builder.add(Arguments.of("annual-review.sh",
                "annual review"));
        builder.add(Arguments.of("annual-review.sh", "docs"));
        builder.add(Arguments.of("maintenance-gates.sh",
                "coverage-check.sh"));
        builder.add(Arguments.of("maintenance-gates.sh",
                "spotbugs"));
        builder.add(Arguments.of("maintenance-gates.sh",
                "dependency:analyze"));
        builder.add(Arguments.of("sbom.sh", "SBOM"));
        builder.add(Arguments.of("sbom.sh", "sha256sum"));
        return builder.build();
    }

    static Stream<Arguments> docTokens() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (String token : new String[]{"hotfix", "backport",
                "fix/", "v3.7.1"}) {
            builder.add(Arguments.of(
                    "docs/operations/maintenance-mode.md", token));
        }
        for (String token : new String[]{"runner-review.sh",
                "evidence", "SEALED_GA", "CLOSED"}) {
            builder.add(Arguments.of(
                    "docs/deployment/runner-review-execution-pack.md",
                    token));
        }
        for (String token : new String[]{"多模型", "多集群",
                "云原生", "RFC"}) {
            builder.add(Arguments.of(
                    "docs/planning/v4-roadmap.md", token));
        }
        for (String token : new String[]{"摘要", "设计", "备选",
                "兼容性"}) {
            builder.add(Arguments.of(
                    "docs/planning/rfc-template.md", token));
        }
        for (String token : new String[]{"annual-review.sh",
                "文档", "基准"}) {
            builder.add(Arguments.of(
                    "docs/operations/annual-review.md", token));
        }
        for (String token : new String[]{"semver", "SBOM",
                "签名"}) {
            builder.add(Arguments.of(
                    "docs/operations/release-hygiene.md", token));
        }
        for (String token : new String[]{"security", "修复",
                "advisory"}) {
            builder.add(Arguments.of(
                    "docs/security/disclosure-policy.md", token));
        }
        for (String token : new String[]{"LOCAL", "v3.7.0"}) {
            builder.add(Arguments.of(
                    "docs/benchmark/maintenance-baseline.md",
                    token));
        }
        return builder.build();
    }

    static Stream<Arguments> adrs() {
        return Stream.of(
                        "ADR-0311-maintenance-mode-and-hotfix-flow.md",
                        "ADR-0312-real-runner-review-execution-pack.md",
                        "ADR-0313-v4-planning-framework.md",
                        "ADR-0314-annual-review-and-capability-rebaseline.md",
                        "ADR-0315-maintenance-quality-gates.md",
                        "ADR-0316-release-hygiene-and-artifacts.md",
                        "ADR-0317-community-and-delivery-readiness.md")
                .map(Arguments::of);
    }

    static Stream<Arguments> maintenanceDocs() {
        return Stream.of(
                        "docs/review/phase57-maintenance-review.md",
                        "docs/release/v3.7.1-rc-maintenance-notes.md")
                .map(Arguments::of);
    }
}
