package io.tieringkv.operations;

import io.tieringkv.transaction.ExecJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** GA 运营收尾与基线（ADR-0309）。 */
class GaOpsClosureTest {

    @Test
    void auditExportContainsGates() {
        String export = GaAuditExport.export(List.of());
        assertThat(export).contains("GateConvergenceV17",
                "SEALED_GA");
    }

    @Test
    void auditExportContainsJournal() {
        ExecJournal journal = new ExecJournal();
        journal.record(2, ExecJournal.Outcome.SUCCESS);
        String export = GaAuditExport.export(journal.records());
        assertThat(export).contains("records=1",
                "SUCCESS");
    }

    @Test
    void gaBaselineReady() {
        assertThat(ProductCompletenessBaseline.gaReady()).isTrue();
    }

    @Test
    void gaDocsExist() {
        assertThat(java.nio.file.Path.of("docs", "release",
                "v3.7.0-ga-release-notes.md").toFile()).exists();
        assertThat(java.nio.file.Path.of("docs", "operations",
                "ga-operations-closure.md").toFile()).exists();
        assertThat(java.nio.file.Path.of("docs", "release",
                "archive", "ga-release-archive.md").toFile())
                .exists();
    }

    @ParameterizedTest(name = "outcome {0}")
    @MethodSource("outcomes")
    void journalOutcomeExported(ExecJournal.Outcome outcome) {
        ExecJournal journal = new ExecJournal();
        journal.record(1, outcome);
        assertThat(GaAuditExport.export(journal.records()))
                .contains(outcome.name());
    }

    static Stream<Arguments> outcomes() {
        return Stream.of(ExecJournal.Outcome.SUCCESS,
                        ExecJournal.Outcome.ROLLED_BACK,
                        ExecJournal.Outcome.FAILED_ROLLBACK)
                .map(Arguments::of);
    }
}
