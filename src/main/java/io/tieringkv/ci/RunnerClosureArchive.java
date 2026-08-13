package io.tieringkv.ci;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 真实 Runner 闭环归档（ADR-0255）：门禁终态快照 + 趋势点 + 告警历史，
 * 生成跨地域趋势报表，供 Phase 49 全量闭环归档。
 */
public final class RunnerClosureArchive {

    /** 门禁终态记录。 */
    public record ClosureRecord(String gateId, String phase,
                                String disposition,
                                String evidence,
                                long timestampMillis) {
    }

    /** 趋势点（跨地域口径，未执行项如实标注 pending）。 */
    public record TrendPoint(String metric, double value,
                             String unit, String scope,
                             long timestampMillis) {
    }

    /** 告警历史。 */
    public record AlertRecord(String gateId, String metric,
                              double value, double threshold,
                              long timestampMillis) {
    }

    private final List<ClosureRecord> records =
            new CopyOnWriteArrayList<>();
    private final List<TrendPoint> trend =
            new CopyOnWriteArrayList<>();
    private final List<AlertRecord> alerts =
            new CopyOnWriteArrayList<>();

    public void record(String gateId, String phase,
                       GateConvergenceV15.Disposition disposition,
                       String evidence) {
        record(gateId, phase, disposition.name(), evidence);
    }

    /** 按字符串终态记录（支持任意收敛表版本的终态枚举）。 */
    public void record(String gateId, String phase,
                       String disposition, String evidence) {
        if (gateId == null || gateId.isBlank()
                || phase == null || phase.isBlank()
                || disposition == null
                || evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException(
                    "gateId, phase, disposition and evidence "
                            + "required");
        }
        records.add(new ClosureRecord(gateId, phase, disposition,
                evidence, System.currentTimeMillis()));
    }

    public void addTrend(String metric, double value, String unit,
                         String scope) {
        if (metric == null || metric.isBlank()
                || unit == null || unit.isBlank()
                || scope == null || scope.isBlank()) {
            throw new IllegalArgumentException(
                    "metric, unit and scope required");
        }
        trend.add(new TrendPoint(metric, value, unit, scope,
                System.currentTimeMillis()));
    }

    public void alert(String gateId, String metric, double value,
                      double threshold) {
        if (gateId == null || gateId.isBlank()
                || metric == null || metric.isBlank()) {
            throw new IllegalArgumentException(
                    "gateId and metric required");
        }
        alerts.add(new AlertRecord(gateId, metric, value,
                threshold, System.currentTimeMillis()));
    }

    public List<ClosureRecord> records() {
        return List.copyOf(records);
    }

    public List<TrendPoint> trendPoints() {
        return List.copyOf(trend);
    }

    public List<AlertRecord> alerts() {
        return List.copyOf(alerts);
    }

    public List<ClosureRecord> forGate(String gateId) {
        return records.stream()
                .filter(record -> record.gateId().equals(gateId))
                .toList();
    }

    /** 跨地域趋势报表：按 metric 分组输出，可直接导出归档。 */
    public String crossRegionTrendReport() {
        StringBuilder builder = new StringBuilder();
        builder.append("metric,value,unit,scope,status")
                .append(System.lineSeparator());
        for (TrendPoint point : trend) {
            builder.append(point.metric()).append(',')
                    .append(point.value()).append(',')
                    .append(point.unit()).append(',')
                    .append(point.scope()).append(",recorded")
                    .append(System.lineSeparator());
        }
        if (trend.isEmpty()) {
            builder.append("(pending: cross-machine metrics await "
                    + "runner execution)")
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    public int size() {
        return records.size();
    }
}
