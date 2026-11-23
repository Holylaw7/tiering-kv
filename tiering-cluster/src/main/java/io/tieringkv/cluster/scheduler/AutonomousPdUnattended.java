package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler.AutonomousPdFullAutomation
        .AutomationResult;
import io.tieringkv.cluster.scheduler.AutonomousPdFullAutomation
        .RiskLevel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自治 PD 无人值守（ADR-0231）：风险自校准（EWMA 回滚率 → 阈值动态
 * 调整）+ 合规证明自动化 + 熔断入口。
 */
public final class AutonomousPdUnattended {

    /** 合规报告。 */
    public record ComplianceReport(long executions,
                                   long rollbacks,
                                   double rollbackRate,
                                   long calibratedThreshold,
                                   String digest) {
    }

    private final AutonomousPdFullAutomation automation;
    private final double ewmaAlpha;
    private final long minThreshold;
    private final long maxThreshold;
    private final List<String> audit = new CopyOnWriteArrayList<>();
    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong rollbacks = new AtomicLong();
    private volatile double rollbackRate;
    private volatile long calibratedThreshold;

    public AutonomousPdUnattended(
            AutonomousPdFullAutomation automation,
            double ewmaAlpha, long minThreshold,
            long maxThreshold) {
        if (automation == null || ewmaAlpha <= 0
                || ewmaAlpha > 1 || minThreshold < 1
                || maxThreshold < minThreshold) {
            throw new IllegalArgumentException(
                    "automation required and calibration "
                            + "parameters invalid");
        }
        this.automation = automation;
        this.ewmaAlpha = ewmaAlpha;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
        this.calibratedThreshold = minThreshold;
    }

    /** 记录执行结果并更新 EWMA 回滚率。 */
    public synchronized void recordOutcome(
            AutomationResult result) {
        executions.incrementAndGet();
        if (result.rolledBack()) {
            rollbacks.incrementAndGet();
        }
        double observed = result.rolledBack() ? 1.0 : 0.0;
        rollbackRate = rollbackRate == 0
                ? observed
                : ewmaAlpha * observed
                + (1 - ewmaAlpha) * rollbackRate;
        calibrate();
    }

    /** 风险自校准：回滚率高 → 阈值降低；低 → 阈值升高。 */
    private void calibrate() {
        long current = calibratedThreshold;
        if (rollbackRate > 0.3) {
            calibratedThreshold = Math.max(minThreshold,
                    current - 1);
        } else if (rollbackRate < 0.05) {
            calibratedThreshold = Math.min(maxThreshold,
                    current + 1);
        }
    }

    /** 无人值守执行：使用校准阈值，护栏/回滚由下层保证。 */
    public AutomationResult execute(Map<String, Long> loads,
                                    long maxLoad) {
        AutomationResult result = automation.execute(loads,
                maxLoad);
        recordOutcome(result);
        audit.add("unattended execute moves="
                + result.moves() + " rolledBack="
                + result.rolledBack()
                + " threshold=" + calibratedThreshold);
        return result;
    }

    /** 生成合规报告（含审计摘要 digest）。 */
    public ComplianceReport complianceReport() {
        String digest = Integer.toHexString(audit.hashCode());
        return new ComplianceReport(executions.get(),
                rollbacks.get(), rollbackRate,
                calibratedThreshold, digest);
    }

    public void manualCircuitBreak(String reason) {
        automation.manualCircuitBreak(reason);
    }

    public void resetCircuit() {
        automation.resetCircuit();
    }

    public boolean circuitBroken() {
        return automation.circuitBroken();
    }

    public long calibratedThreshold() {
        return calibratedThreshold;
    }

    public List<String> audit() {
        return List.copyOf(audit);
    }
}
