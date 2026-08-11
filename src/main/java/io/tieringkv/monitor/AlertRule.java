package io.tieringkv.monitor;

/** 告警规则（Goal 7）：指标阈值 + 等级。 */
public record AlertRule(String metric, double threshold,
                        boolean greaterThan, Level level) {

    public enum Level {
        WARN,
        CRITICAL
    }

    public boolean fires(long value) {
        return greaterThan ? value > threshold : value < threshold;
    }
}
