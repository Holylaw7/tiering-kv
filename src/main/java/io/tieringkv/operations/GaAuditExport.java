package io.tieringkv.operations;

import io.tieringkv.ci.GateConvergenceV17;
import io.tieringkv.transaction.ExecJournal;

import java.util.List;

/** GA 审计导出（ADR-0309）：门禁终态 + 事务审计摘要。 */
public final class GaAuditExport {

    private GaAuditExport() {
    }

    public static String export(List<ExecJournal.ExecRecord> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("GA AUDIT EXPORT").append(System.lineSeparator());
        builder.append("== gate dispositions ==")
                .append(System.lineSeparator());
        builder.append(GateConvergenceV17.summary());
        builder.append("== exec journal ==")
                .append(System.lineSeparator());
        builder.append("records=").append(records.size())
                .append(System.lineSeparator());
        for (ExecJournal.ExecRecord record : records) {
            builder.append(record.txnId()).append(':')
                    .append(record.commandCount()).append(':')
                    .append(record.outcome())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }
}
