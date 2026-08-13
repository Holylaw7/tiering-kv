package io.tieringkv.ci;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 发布记录归档（ADR-0248）：门禁执行结果 + 发布记录，供收敛表 v14
 * 审计与趋势报表。
 */
public final class ReleaseRecordArchive {

    /** 发布记录。 */
    public record ReleaseRecord(String version, String gateId,
                                boolean passed, String evidence,
                                long timestampMillis) {
    }

    private final List<ReleaseRecord> records =
            new CopyOnWriteArrayList<>();

    public void record(String version, String gateId,
                       boolean passed, String evidence) {
        if (version == null || version.isBlank()
                || gateId == null || gateId.isBlank()
                || evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException(
                    "version, gateId and evidence required");
        }
        records.add(new ReleaseRecord(version, gateId, passed,
                evidence, System.currentTimeMillis()));
    }

    public List<ReleaseRecord> records() {
        return List.copyOf(records);
    }

    public List<ReleaseRecord> forVersion(String version) {
        return records.stream()
                .filter(record -> record.version()
                        .equals(version))
                .toList();
    }

    public int size() {
        return records.size();
    }
}
