package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler.RegulatoryKnowledgeBase
        .ClauseDiff;
import io.tieringkv.cluster.scheduler.RegulatoryKnowledgeBase
        .RegulationDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 监管法规库 + 差异报告（ADR-0259）：版本化 + 差异 + 校验 + 轮换。 */
class RegulatoryKnowledgeBaseTest {

    private RegulatoryKnowledgeBase base() {
        RegulatoryKnowledgeBase base =
                new RegulatoryKnowledgeBase();
        base.registerVersion("GDPR", "v1",
                List.of("A17: deletion", "A5: purpose", "A32: security"));
        base.registerVersion("GDPR", "v2",
                List.of("A17: deletion", "A5: purpose",
                        "A32: security", "A33: breach"));
        base.registerVersion("PIPL", "v1",
                List.of("P1: consent", "P2: minimization"));
        base.registerVersion("PIPL", "v2",
                List.of("P1: consent", "P2: minimization",
                        "P3: portability"));
        base.registerVersion("CCPA", "v1",
                List.of("C1: notice", "C2: access"));
        base.registerVersion("CCPA", "v2",
                List.of("C1: notice", "C2: access",
                        "C3: deletion"));
        return base;
    }

    @Test
    void registerAndActiveVersion() {
        RegulatoryKnowledgeBase base = base();
        RegulationDocument active = base.active("GDPR");
        assertThat(active.version()).isEqualTo("v2");
        assertThat(active.clauses()).hasSize(4);
    }

    @Test
    void digestVerificationPasses() {
        RegulatoryKnowledgeBase base = base();
        assertThat(base.verify("GDPR", "v1")).isTrue();
        assertThat(base.verify("GDPR", "v2")).isTrue();
    }

    @Test
    void diffDetectsAddedRemovedChanged() {
        RegulatoryKnowledgeBase base = base();
        ClauseDiff diff = base.diff("GDPR", "v1", "v2");
        assertThat(diff.added()).contains("A33: breach");
        assertThat(diff.removed()).isEmpty();
    }

    @Test
    void diffReportIsExportable() {
        RegulatoryKnowledgeBase base = base();
        String report = base.diffReport("GDPR", "v1", "v2");
        assertThat(report).contains("GDPR");
        assertThat(report).contains("v1 -> v2");
        assertThat(report).contains("added:");
    }

    @Test
    void retireMarksDocumentButKeepsVerifiable() {
        RegulatoryKnowledgeBase base = base();
        base.retire("GDPR", "v1");
        RegulationDocument retired = base.versions("GDPR")
                .get(0);
        assertThat(retired.retired()).isTrue();
        assertThat(base.verify("GDPR", "v1")).isTrue();
    }

    @Test
    void evidenceViaMappingEngine() {
        RegulatoryKnowledgeBase base = base();
        RegulatoryMappingEngine engine =
                new RegulatoryMappingEngine();
        engine.registerRule("GDPR", "A17", "delete");
        base.attachMappingEngine(engine);
        assertThat(base.evidence("delete"))
                .contains("GDPR/A17");
    }

    @Test
    void certificateIssuedFromActiveDigest() {
        RegulatoryKnowledgeBase base = base();
        RegulatoryComplianceCertificate certificate =
                new RegulatoryComplianceCertificate();
        base.attachCertificate(certificate);
        var cert = base.issueCertificate("GDPR", "auditor");
        assertThat(cert.issuer()).isEqualTo("auditor");
        assertThat(certificate.verify(cert)).isTrue();
    }

    @Test
    void unknownRegulationRejected() {
        RegulatoryKnowledgeBase base = base();
        assertThatThrownBy(() -> base.active("NOPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceWithoutEngineRejected() {
        RegulatoryKnowledgeBase base = base();
        assertThatThrownBy(() -> base.evidence("delete"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankRegistrationRejected() {
        assertThatThrownBy(() -> base().registerVersion("",
                "v1", List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "regulation={0} version={1} clauses={2}")
    @MethodSource("versionMatrix")
    void versionedRegistrationAndVerification(String regulation,
                                              String version,
                                              List<String> clauses) {
        RegulatoryKnowledgeBase base = base();
        RegulationDocument document = base.registerVersion(
                regulation, version, clauses);
        assertThat(document.digest()).isNotBlank();
        assertThat(base.verify(regulation, version)).isTrue();
        assertThat(base.active(regulation).version())
                .isEqualTo(version);
    }

    @ParameterizedTest(name = "regulation={0} from={1} to={2} "
            + "added={3} removed={4} changed={5}")
    @MethodSource("diffMatrix")
    void diffMatrixProducesExpectedSizes(String regulation,
                                         String from,
                                         String to,
                                         int added, int removed,
                                         int changed) {
        RegulatoryKnowledgeBase base = base();
        ClauseDiff diff = base.diff(regulation, from, to);
        assertThat(diff.added()).hasSize(added);
        assertThat(diff.removed()).hasSize(removed);
        assertThat(diff.changed()).hasSize(changed);
    }

    @ParameterizedTest(name = "regulation={0} from={1} to={2}")
    @MethodSource("reportMatrix")
    void diffReportContainsVersions(String regulation,
                                    String from, String to) {
        RegulatoryKnowledgeBase base = base();
        String report = base.diffReport(regulation, from, to);
        assertThat(report).contains(regulation);
        assertThat(report).contains(from + " -> " + to);
    }

    @ParameterizedTest(name = "invalid {0}")
    @MethodSource("validationMatrix")
    void invalidInputsRejected(String caseName) {
        assertThatThrownBy(() -> {
            switch (caseName) {
                case "blank-regulation" -> base().registerVersion(
                        "", "v1", List.of("x"));
                case "blank-version" -> base().registerVersion(
                        "GDPR", "", List.of("x"));
                case "empty-clauses" -> base().registerVersion(
                        "GDPR", "v9", List.of());
                case "blank-clause" -> base().registerVersion(
                        "GDPR", "v9", List.of("  "));
                case "unknown-version" -> base().diff("GDPR",
                        "v1", "v9");
                case "unknown-regulation-diff" -> base().diff(
                        "NOPE", "v1", "v2");
                case "retire-unknown" -> base().retire("GDPR",
                        "v9");
                case "null-engine" -> base()
                        .attachMappingEngine(null);
                case "null-certificate" -> base()
                        .attachCertificate(null);
                case "issue-without-cert" -> base()
                        .issueCertificate("GDPR", "auditor");
                default -> throw new IllegalArgumentException(
                        "unknown case");
            }
        }).isInstanceOf(RuntimeException.class);
    }

    static Stream<Arguments> versionMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (String regulation : List.of("GDPR", "PIPL", "CCPA")) {
            for (String version : List.of("v1", "v2", "v3")) {
                for (int variant = 0; variant < 3; variant++) {
                    builder.add(Arguments.of(regulation, version,
                            List.of("C" + variant + ": rule-"
                                    + regulation + "-"
                                    + version)));
                }
            }
        }
        return builder.build();
    }

    static Stream<Arguments> diffMatrix() {
        return Stream.of(
                Arguments.of("GDPR", "v1", "v2", 1, 0, 0),
                Arguments.of("GDPR", "v2", "v1", 0, 1, 0),
                Arguments.of("GDPR", "v1", "v1", 0, 0, 0),
                Arguments.of("PIPL", "v1", "v1", 0, 0, 0),
                Arguments.of("PIPL", "v1", "v2", 1, 0, 0),
                Arguments.of("PIPL", "v2", "v1", 0, 1, 0),
                Arguments.of("CCPA", "v1", "v2", 1, 0, 0),
                Arguments.of("CCPA", "v2", "v1", 0, 1, 0));
    }

    static Stream<Arguments> reportMatrix() {
        return Stream.of(
                Arguments.of("GDPR", "v1", "v2"),
                Arguments.of("GDPR", "v2", "v1"),
                Arguments.of("GDPR", "v1", "v1"),
                Arguments.of("PIPL", "v1", "v1"),
                Arguments.of("PIPL", "v1", "v2"),
                Arguments.of("PIPL", "v2", "v1"),
                Arguments.of("CCPA", "v1", "v1"),
                Arguments.of("CCPA", "v1", "v2"),
                Arguments.of("CCPA", "v2", "v1"),
                Arguments.of("GDPR", "v2", "v2"));
    }

    static Stream<Arguments> validationMatrix() {
        return Stream.of("blank-regulation", "blank-version",
                        "empty-clauses", "blank-clause",
                        "unknown-version", "unknown-regulation-diff",
                        "retire-unknown", "null-engine",
                        "null-certificate", "issue-without-cert")
                .map(Arguments::of);
    }
}
