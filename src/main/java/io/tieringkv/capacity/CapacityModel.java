package io.tieringkv.capacity;

/**
 * 生产容量模型（ADR-0322，TD-019）：吞吐/延迟/内存/磁盘四维估算。
 *
 * <p>输入为运维预期，输出为容量预算；公式为可计算可验证的简化模型，
 * 常数经基准数据校准（docs/benchmark/capacity-model.md 联动）。
 */
public final class CapacityModel {

    /** 每 key 内存开销（索引 + 元数据 + 对齐，字节）。 */
    private static final double KEY_OVERHEAD_BYTES = 96;
    /** 磁盘保留时间换算：天数 → 秒。 */
    private static final double SECONDS_PER_DAY = 86_400;
    /** 吞吐 headroom（应对突发）。 */
    private static final double QPS_HEADROOM = 0.2;
    /** 延迟预算（ms）：读为主更紧。 */
    private static final double READ_LATENCY_BUDGET_MS = 5;
    private static final double WRITE_LATENCY_BUDGET_MS = 10;

    private CapacityModel() {
    }

    /** 容量模型输入。 */
    public record Input(
            double qps,
            int valueBytes,
            double readRatio,
            int replicationFactor,
            int retentionDays,
            long activeKeys) {
        public Input {
            if (qps < 0 || valueBytes < 0) {
                throw new IllegalArgumentException(
                        "qps/valueBytes >= 0");
            }
            if (readRatio < 0 || readRatio > 1) {
                throw new IllegalArgumentException(
                        "readRatio in [0,1]");
            }
            if (replicationFactor < 1 || retentionDays < 0
                    || activeKeys < 0) {
                throw new IllegalArgumentException(
                        "replicationFactor >= 1, "
                                + "retentionDays/activeKeys >= 0");
            }
        }
    }

    /** 容量估算输出。 */
    public record Estimate(
            double memoryBytes,
            double diskBytes,
            double requiredQpsCapacity,
            double p99LatencyBudgetMs) {
    }

    public static Estimate estimate(Input input) {
        double valueWithOverhead =
                input.valueBytes() + KEY_OVERHEAD_BYTES;
        double memoryBytes = input.activeKeys()
                * valueWithOverhead
                * input.replicationFactor();
        double writeRatio = 1 - input.readRatio();
        double diskBytes = input.qps()
                * writeRatio
                * input.valueBytes()
                * input.retentionDays()
                * SECONDS_PER_DAY
                * input.replicationFactor();
        double requiredQps = input.qps()
                * (1 + QPS_HEADROOM);
        double latencyBudget = input.readRatio() >= 0.5
                ? READ_LATENCY_BUDGET_MS
                : WRITE_LATENCY_BUDGET_MS;
        return new Estimate(memoryBytes, diskBytes, requiredQps,
                latencyBudget);
    }
}
