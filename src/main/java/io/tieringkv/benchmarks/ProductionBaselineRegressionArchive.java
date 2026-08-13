package io.tieringkv.benchmarks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TiKV 回归归档执行器（ADR-0260）：多机部署回归快照 + 趋势点 +
 * 告警历史 + 归档报表；对比口径如实注明（本地进程内 / 跨机 Runner）。
 */
public final class ProductionBaselineRegressionArchive {

    /** 回归快照。 */
    public record BaselineSnapshot(String phase, long getP50,
                                   long getP95, long getP99,
                                   long setP50, long setP95,
                                   long setP99,
                                   double throughputOps,
                                   long memoryBytes,
                                   long rttMillis, long rtoMillis,
                                   long rpoMillis, String scope,
                                   String evidence) {
    }

    /** 趋势点。 */
    public record Trend(String metric, double value,
                        long timestampMillis) {
    }

    /** 告警历史。 */
    public record Alert(String metric, double value,
                        double threshold,
                        long timestampMillis) {
    }

    private final List<BaselineSnapshot> snapshots =
            new CopyOnWriteArrayList<>();
    private final List<Trend> trends =
            new CopyOnWriteArrayList<>();
    private final List<Alert> alerts =
            new CopyOnWriteArrayList<>();

    /** 记录一次回归快照（scope: LOCAL / CROSS_MACHINE / PENDING）。 */
    public BaselineSnapshot addSnapshot(
            String phase, long getP50, long getP95, long getP99,
            long setP50, long setP95, long setP99,
            double throughputOps, long memoryBytes,
            long rttMillis, long rtoMillis, long rpoMillis,
            String scope, String evidence) {
        if (phase == null || phase.isBlank()
                || scope == null || scope.isBlank()
                || evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException(
                    "phase, scope and evidence required");
        }
        BaselineSnapshot snapshot = new BaselineSnapshot(phase,
                getP50, getP95, getP99, setP50, setP95, setP99,
                throughputOps, memoryBytes, rttMillis, rtoMillis,
                rpoMillis, scope, evidence);
        snapshots.add(snapshot);
        return snapshot;
    }

    public void addTrend(String metric, double value) {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException(
                    "metric required");
        }
        trends.add(new Trend(metric, value,
                System.currentTimeMillis()));
    }

    /** 告警：value 超过 threshold 时记录并返回 true。 */
    public boolean alertIf(String metric, double value,
                           double threshold) {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException(
                    "metric required");
        }
        if (value > threshold) {
            alerts.add(new Alert(metric, value, threshold,
                    System.currentTimeMillis()));
            return true;
        }
        return false;
    }

    public List<BaselineSnapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    public List<Trend> trends() {
        return List.copyOf(trends);
    }

    public List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    public BaselineSnapshot latest() {
        return snapshots.isEmpty()
                ? null : snapshots.get(snapshots.size() - 1);
    }

    /** 归档报表：逐快照输出 + 口径注明，可直接导出。 */
    public String report() {
        StringBuilder builder = new StringBuilder();
        builder.append("phase,scope,get_p50,get_p95,get_p99,"
                + "set_p50,set_p95,set_p99,throughput,memory,"
                + "rtt,rto,rpo,evidence")
                .append(System.lineSeparator());
        for (BaselineSnapshot snapshot : snapshots) {
            builder.append(snapshot.phase()).append(',')
                    .append(snapshot.scope()).append(',')
                    .append(snapshot.getP50()).append(',')
                    .append(snapshot.getP95()).append(',')
                    .append(snapshot.getP99()).append(',')
                    .append(snapshot.setP50()).append(',')
                    .append(snapshot.setP95()).append(',')
                    .append(snapshot.setP99()).append(',')
                    .append(snapshot.throughputOps()).append(',')
                    .append(snapshot.memoryBytes()).append(',')
                    .append(snapshot.rttMillis()).append(',')
                    .append(snapshot.rtoMillis()).append(',')
                    .append(snapshot.rpoMillis()).append(',')
                    .append(snapshot.evidence())
                    .append(System.lineSeparator());
        }
        if (snapshots.isEmpty()) {
            builder.append("(no snapshots yet)")
                    .append(System.lineSeparator());
        }
        builder.append("alerts=").append(alerts.size())
                .append(", trends=").append(trends.size())
                .append(System.lineSeparator());
        return builder.toString();
    }
}
