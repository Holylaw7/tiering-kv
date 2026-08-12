package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration.PlanResult;
import io.tieringkv.cluster.scheduler.RebalanceScheduler.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自治 PD 全自动（ADR-0224）：风险分级 → 低风险自动执行；
 * 高风险进入审批队列；护栏/回滚/审计复用 GlobalAutonomyPdIntegration，
 * 保留人工熔断入口。
 */
public final class AutonomousPdFullAutomation {

    /** 风险等级。 */
    public enum RiskLevel {
        LOW,
        HIGH
    }

    /** 自动执行结果。 */
    public record AutomationResult(RiskLevel risk,
                                   int moves, boolean executed,
                                   boolean queuedForApproval,
                                   boolean rolledBack) {
    }

    private final GlobalAutonomyPdIntegration integration;
    private final int lowRiskMaxMoves;
    private final List<Move> approvalQueue =
            new CopyOnWriteArrayList<>();
    private final List<String> audit =
            new CopyOnWriteArrayList<>();
    private volatile boolean circuitBroken;

    public AutonomousPdFullAutomation(
            GlobalAutonomyPdIntegration integration,
            int lowRiskMaxMoves) {
        if (integration == null || lowRiskMaxMoves < 0) {
            throw new IllegalArgumentException(
                    "integration required and lowRiskMaxMoves "
                            + "must be non-negative");
        }
        this.integration = integration;
        this.lowRiskMaxMoves = lowRiskMaxMoves;
    }

    /** 评估风险：计划动作数超过低风险阈值 → HIGH。 */
    public RiskLevel assessRisk(Map<String, Long> loads,
                                long maxLoad) {
        if (loads == null || loads.isEmpty()) {
            throw new IllegalArgumentException(
                    "loads required");
        }
        int moves = new RebalanceScheduler().plan(loads, maxLoad)
                .size();
        return moves > lowRiskMaxMoves
                ? RiskLevel.HIGH : RiskLevel.LOW;
    }

    /** 自动执行：低风险直接执行；高风险入审批队列。 */
    public synchronized AutomationResult execute(
            Map<String, Long> loads, long maxLoad) {
        if (circuitBroken) {
            audit.add("circuit broken: rejected");
            return new AutomationResult(assessRisk(loads, maxLoad),
                    0, false, false, false);
        }
        RiskLevel risk = assessRisk(loads, maxLoad);
        if (risk == RiskLevel.HIGH) {
            approvalQueue.addAll(new RebalanceScheduler()
                    .plan(loads, maxLoad));
            audit.add("high risk: queued "
                    + approvalQueue.size() + " moves");
            return new AutomationResult(risk, 0, false, true,
                    false);
        }
        PlanResult result = integration.planAndExecute(loads);
        audit.add("auto executed risk=LOW moves="
                + result.executed()
                + " rolledBack=" + result.rolledBack());
        return new AutomationResult(risk, result.executed(), true,
                false, result.rolledBack());
    }

    /** 人工批准审批队列后执行（仍受护栏约束）。 */
    public synchronized AutomationResult approvePending(
            Map<String, Long> loads, long maxLoad) {
        if (approvalQueue.isEmpty()) {
            return new AutomationResult(RiskLevel.LOW, 0, false,
                    false, false);
        }
        PlanResult result = integration.planAndExecute(loads);
        approvalQueue.clear();
        audit.add("approved pending executed="
                + result.executed());
        return new AutomationResult(RiskLevel.HIGH,
                result.executed(), true, false,
                result.rolledBack());
    }

    /** 人工熔断：冻结自治，任何执行请求被拒绝。 */
    public void manualCircuitBreak(String reason) {
        circuitBroken = true;
        audit.add("manual circuit break: " + reason);
    }

    public void resetCircuit() {
        circuitBroken = false;
        audit.add("circuit reset");
    }

    public boolean circuitBroken() {
        return circuitBroken;
    }

    public List<Move> approvalQueue() {
        return List.copyOf(approvalQueue);
    }

    public List<String> audit() {
        return List.copyOf(audit);
    }
}
