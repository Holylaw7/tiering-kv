package io.tieringkv.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流量自治控制器（ADR-0151）：基于预测动态调整地域配额，
 * 限幅 + 熔断 + 回滚。
 */
public final class AutonomousTrafficController {

    public enum Outcome {
        APPLIED,
        REJECTED
    }

    /** 调整记录：地域 + 调整前后配额 + 结果。 */
    public record Adjustment(String region, long previous,
                             long target, Outcome outcome,
                             String reason) {
    }

    private final RegionQuota quota;
    private final double maxChangeFraction;
    private final long minQuota;
    private final long maxQuota;
    private final Map<String, Long> previous =
            new ConcurrentHashMap<>();
    private volatile boolean circuitOpen;
    private volatile String circuitReason;

    public AutonomousTrafficController(RegionQuota quota,
                                       double maxChangeFraction,
                                       long minQuota, long maxQuota) {
        if (maxChangeFraction <= 0 || maxChangeFraction > 1) {
            throw new IllegalArgumentException(
                    "max change fraction must be in (0,1]");
        }
        if (minQuota < 0 || maxQuota < minQuota) {
            throw new IllegalArgumentException(
                    "invalid quota bounds");
        }
        this.quota = quota;
        this.maxChangeFraction = maxChangeFraction;
        this.minQuota = minQuota;
        this.maxQuota = maxQuota;
    }

    /** 单地域配额调整：限幅到 [min,max] 与单步比例上限。 */
    public synchronized Adjustment adjust(String region,
                                          long targetQuota) {
        if (circuitOpen) {
            return new Adjustment(region, quota.quota(region),
                    targetQuota, Outcome.REJECTED,
                    "circuit open: " + circuitReason);
        }
        long current = quota.quota(region);
        if (current == 0) {
            quota.setQuota(region, minQuota);
            current = minQuota;
        }
        long clamped = Math.max(minQuota,
                Math.min(maxQuota, targetQuota));
        long maxDelta = Math.max(1,
                Math.round(current * maxChangeFraction));
        long delta = clamped - current;
        if (Math.abs(delta) > maxDelta) {
            clamped = current + Long.signum(delta) * maxDelta;
        }
        previous.putIfAbsent(region, current);
        quota.setQuota(region, clamped);
        return new Adjustment(region, current, clamped,
                Outcome.APPLIED, "");
    }

    /** 熔断：拒绝后续调整直到 reset。 */
    public void openCircuit(String reason) {
        circuitOpen = true;
        circuitReason = reason;
    }

    public void resetCircuit() {
        circuitOpen = false;
        circuitReason = null;
    }

    public boolean circuitOpen() {
        return circuitOpen;
    }

    public RegionQuota quota() {
        return quota;
    }

    /** 回滚：恢复全部已应用调整。 */
    public synchronized void rollback() {
        previous.forEach(quota::setQuota);
        previous.clear();
    }
}
