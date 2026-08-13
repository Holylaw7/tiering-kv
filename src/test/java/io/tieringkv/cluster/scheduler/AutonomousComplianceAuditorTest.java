package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler.AutonomousComplianceAuditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自治合规自动化（ADR-0238）：审计链 + 签名 + 外部审计接口。 */
class AutonomousComplianceAuditorTest {

    @Test
    void recordSignsEntry() {
        AutonomousComplianceAuditor auditor = auditor();
        String signature = auditor.record("executed moves=3");
        assertThat(signature).hasSize(64);
        assertThat(signature).matches("[0-9a-f]{64}");
    }

    @Test
    void auditChainAppendOnly() {
        AutonomousComplianceAuditor auditor = auditor();
        auditor.record("a");
        auditor.record("b");
        assertThat(auditor.size()).isEqualTo(2);
        assertThat(auditor.exportAudit()).hasSize(2);
    }

    @Test
    void exportImmutably() {
        AutonomousComplianceAuditor auditor = auditor();
        auditor.record("a");
        List<String> exported = auditor.exportAudit();
        assertThatThrownBy(() -> exported.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void verifyValidChain() {
        AutonomousComplianceAuditor auditor = auditor();
        auditor.record("executed moves=1");
        auditor.record("executed moves=2");
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
    }

    @Test
    void verifyWrongSizeRejected() {
        AutonomousComplianceAuditor auditor = auditor();
        auditor.record("a");
        assertThat(auditor.verify(List.of())).isFalse();
    }

    @Test
    void verifyTamperedEntryRejected() {
        AutonomousComplianceAuditor auditor = auditor();
        auditor.record("a");
        auditor.record("b");
        List<String> exported = new ArrayList<>(
                auditor.exportAudit());
        exported.set(1, "tampered|" + "0".repeat(64));
        assertThat(auditor.verify(exported)).isFalse();
    }

    @Test
    void verifyTamperedSignatureRejected() {
        AutonomousComplianceAuditor auditor = auditor();
        auditor.record("a");
        List<String> exported = new ArrayList<>(
                auditor.exportAudit());
        String entry = exported.get(0);
        int bar = entry.lastIndexOf('|');
        exported.set(0, entry.substring(0, bar)
                + "|" + "0".repeat(64));
        assertThat(auditor.verify(exported)).isFalse();
    }

    @Test
    void emptyAuditVerify() {
        AutonomousComplianceAuditor auditor = auditor();
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
        assertThat(auditor.size()).isZero();
    }

    @Test
    void nullEntryRejected() {
        assertThatThrownBy(() -> auditor().record(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullExportRejected() {
        assertThatThrownBy(() -> auditor().verify(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleRecordsChain() {
        AutonomousComplianceAuditor auditor = auditor();
        for (int i = 0; i < 10; i++) {
            auditor.record("event " + i);
        }
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
    }

    @Test
    void deterministicSignature() {
        AutonomousComplianceAuditor auditor = auditor();
        String first = auditor.record("same entry");
        AutonomousComplianceAuditor other = auditor();
        String second = other.record("same entry");
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest(name = "entries={0} prefix={1}")
    @CsvSource({
            "1,executed",
            "2,executed",
            "3,executed",
            "5,executed",
            "10,executed",
            "1,rolled back",
            "2,rolled back",
            "3,rolled back",
            "5,rolled back",
            "10,rolled back",
            "1,circuit",
            "2,circuit",
            "3,circuit",
            "5,circuit",
            "10,circuit",
            "2,threshold",
            "4,threshold",
            "6,threshold",
            "8,threshold",
            "10,threshold",
            "1,compliance",
            "2,compliance",
            "3,compliance",
            "5,compliance",
            "10,compliance",
            "4,audit",
            "6,audit",
            "8,audit",
            "12,audit",
            "16,audit",
            "2,move",
            "4,move",
            "6,move",
            "8,move",
            "10,move",
            "3,scale",
            "6,scale",
            "9,scale",
            "12,scale",
            "15,scale"
    })
    void parameterizedRecordMatrix(int entries, String prefix) {
        AutonomousComplianceAuditor auditor = auditor();
        for (int i = 0; i < entries; i++) {
            auditor.record(prefix + " " + i);
        }
        assertThat(auditor.size()).isEqualTo(entries);
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
        assertThat(auditor.exportAudit().get(0))
                .startsWith(prefix);
    }

    @ParameterizedTest(name = "entry {0}")
    @CsvSource({
            "a",
            "executed moves=1",
            "rolled back moves=0",
            "circuit broken review",
            "threshold=5",
            "compliance report",
            "audit chain entry",
            "scale out topology",
            "window function lag",
            "tso arbitration",
            "credential handshake ok",
            "gate convergence v12",
            "multi cloud quorum",
            "rollback protected",
            "unattended execute"
    })
    void parameterizedSignatureLength(String entry) {
        AutonomousComplianceAuditor auditor = auditor();
        String signature = auditor.record(entry);
        assertThat(signature).hasSize(64);
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
    }

    @ParameterizedTest(name = "entries {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10, 20, 50, 100})
    void parameterizedChainSizes(int entries) {
        AutonomousComplianceAuditor auditor = auditor();
        for (int i = 0; i < entries; i++) {
            auditor.record("event " + i);
        }
        List<String> exported = auditor.exportAudit();
        assertThat(exported).hasSize(entries);
        assertThat(auditor.verify(exported)).isTrue();
    }

    private static AutonomousComplianceAuditor auditor() {
        return new AutonomousComplianceAuditor();
    }
}
