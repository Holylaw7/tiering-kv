package io.tieringkv.capacity.ai;

import io.tieringkv.gateway.AutonomousTrafficController;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 全球受限自治编排器（ADR-0157）：容量 + 流量 + 重分片联动，
 * 策略围栏（日预算 / 地域上限 / 熔断 / 回滚）。
 */
public final class GlobalAutonomyOrchestrator {

    /** 全局策略围栏。 */
    public record Policy(int maxActionsPerDay, int maxRegionsAffected,
                         boolean allowReshard) {

        public Policy {
            if (maxActionsPerDay < 1 || maxRegionsAffected < 1) {
                throw new IllegalArgumentException(
                        "limits must be positive");
            }
        }
    }

    public enum Outcome {
        EXECUTED,
        REJECTED,
        ROLLED_BACK
    }

    /** 动作结果：结果 + 动作 + 原因。 */
    public record ActionResult(Outcome outcome, String action,
                               String reason) {
    }

    private final AutonomousCapacityController capacity;
    private final AutonomousTrafficController traffic;
    private final Policy policy;
    private final Function<String, Boolean> reshardExecutor;
    private final int initialNodes;
    private final Set<String> affectedRegions =
            ConcurrentHashMap.newKeySet();
    private final List<String> failures =
            new CopyOnWriteArrayList<>();
    private int actionsToday;
    private volatile boolean circuitOpen;
    private volatile String circuitReason;

    public GlobalAutonomyOrchestrator(
            AutonomousCapacityController capacity,
            AutonomousTrafficController traffic, Policy policy,
            Function<String, Boolean> reshardExecutor) {
        this.capacity = capacity;
        this.traffic = traffic;
        this.policy = policy;
        this.reshardExecutor = reshardExecutor;
        this.initialNodes = capacity.currentNodes();
    }

    public synchronized ActionResult applyCapacity(
            AutoCapacityAdvisor.Advice advice) {
        if (rejectedByCircuit()) {
            return new ActionResult(Outcome.REJECTED, "capacity",
                    "circuit open: " + circuitReason);
        }
        if (actionsToday >= policy.maxActionsPerDay()) {
            return reject("capacity", "daily budget exhausted");
        }
        AutonomousCapacityController.Adjustment adjustment =
                capacity.apply(advice);
        if (adjustment.outcome()
                == AutonomousCapacityController.Outcome.EXECUTED) {
            actionsToday++;
            return new ActionResult(Outcome.EXECUTED, "capacity", "");
        }
        return reject("capacity", adjustment.reason());
    }

    public synchronized ActionResult applyTraffic(String region,
                                                  long targetQuota) {
        if (rejectedByCircuit()) {
            return new ActionResult(Outcome.REJECTED, "traffic",
                    "circuit open: " + circuitReason);
        }
        int newRegions = affectedRegions.contains(region) ? 0 : 1;
        if (affectedRegions.size() + newRegions
                > policy.maxRegionsAffected()) {
            return reject("traffic",
                    "region cap exceeded: " + region);
        }
        if (actionsToday >= policy.maxActionsPerDay()) {
            return reject("traffic", "daily budget exhausted");
        }
        AutonomousTrafficController.Adjustment adjustment =
                traffic.adjust(region, targetQuota);
        if (adjustment.outcome()
                == AutonomousTrafficController.Outcome.APPLIED) {
            actionsToday++;
            affectedRegions.add(region);
            return new ActionResult(Outcome.EXECUTED, "traffic", "");
        }
        return reject("traffic", adjustment.reason());
    }

    public synchronized ActionResult applyReshard(String planId) {
        if (!policy.allowReshard()) {
            return reject("reshard", "reshard disabled by policy");
        }
        if (rejectedByCircuit()) {
            return new ActionResult(Outcome.REJECTED, "reshard",
                    "circuit open: " + circuitReason);
        }
        if (actionsToday >= policy.maxActionsPerDay()) {
            return reject("reshard", "daily budget exhausted");
        }
        try {
            boolean ok = reshardExecutor.apply(planId);
            if (!ok) {
                return new ActionResult(Outcome.ROLLED_BACK,
                        "reshard", "executor rolled back");
            }
            actionsToday++;
            return new ActionResult(Outcome.EXECUTED, "reshard", "");
        } catch (RuntimeException e) {
            failures.add("reshard:" + planId + ":" + e.getMessage());
            return new ActionResult(Outcome.ROLLED_BACK, "reshard",
                    e.getMessage());
        }
    }

    /** 回滚：流量恢复原始配额 + 容量恢复初始节点。 */
    public synchronized void rollback() {
        traffic.rollback();
        capacity.restore(initialNodes);
    }

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

    /** 日切：重置日预算与受影响地域集合。 */
    public synchronized void newDay() {
        actionsToday = 0;
        affectedRegions.clear();
    }

    public synchronized int actionsToday() {
        return actionsToday;
    }

    public Set<String> affectedRegions() {
        return Set.copyOf(affectedRegions);
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    private boolean rejectedByCircuit() {
        return circuitOpen;
    }

    private ActionResult reject(String action, String reason) {
        failures.add(action + ":" + reason);
        return new ActionResult(Outcome.REJECTED, action, reason);
    }
}
