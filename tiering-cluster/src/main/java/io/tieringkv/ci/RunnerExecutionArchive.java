package io.tieringkv.ci;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 真实 Runner 执行记录归档（ADR-0241）：可执行项结果（时间/状态/证据），
 * 供门禁收敛表 v13 审计。
 */
public final class RunnerExecutionArchive {

    /** 执行记录。 */
    public record ExecutionRecord(String gateId, boolean passed,
                                  String evidence,
                                  long timestampMillis) {
    }

    private final List<ExecutionRecord> records =
            new CopyOnWriteArrayList<>();

    /** 归档一条执行记录。 */
    public void record(String gateId, boolean passed,
                       String evidence) {
        if (gateId == null || gateId.isBlank()
                || evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException(
                    "gateId and evidence required");
        }
        records.add(new ExecutionRecord(gateId, passed,
                evidence, System.currentTimeMillis()));
    }

    public List<ExecutionRecord> records() {
        return List.copyOf(records);
    }

    public List<ExecutionRecord> forGate(String gateId) {
        return records.stream()
                .filter(record -> record.gateId().equals(gateId))
                .toList();
    }

    public int size() {
        return records.size();
    }
}
