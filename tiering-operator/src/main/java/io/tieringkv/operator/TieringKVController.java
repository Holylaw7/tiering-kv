package io.tieringkv.operator;

import java.util.List;
import java.util.function.Consumer;

/** Operator 控制器（ADR-0107）：reconcile 循环 → 计划 → 动作执行。 */
public final class TieringKVController {

    private final OperatorPlanner planner;
    private final Consumer<OperatorAction> actionSink;
    private final ClusterStateMachine stateMachine;
    private long generation;

    public TieringKVController(Consumer<OperatorAction> actionSink) {
        this(actionSink, new ClusterStateMachine(
                ClusterPhase.PENDING));
    }

    public TieringKVController(Consumer<OperatorAction> actionSink,
                               ClusterStateMachine stateMachine) {
        this.planner = new OperatorPlanner();
        this.actionSink = actionSink;
        this.stateMachine = stateMachine;
    }

    public ClusterPhase phase() {
        return stateMachine.current();
    }

    public long applySpec(TieringKVClusterSpec spec,
                          TieringKVClusterStatus current) {
        generation++;
        advancePhase(spec, current);
        List<OperatorAction> actions = planner.plan(spec, current,
                generation);
        for (OperatorAction action : actions) {
            actionSink.accept(action);
        }
        return generation;
    }

    /** 按期望/实际状态推进状态机（ADR-0322）。 */
    private void advancePhase(TieringKVClusterSpec spec,
                              TieringKVClusterStatus current) {
        int ready = current.readyMetadata() + current.readyStorage()
                + current.readyGateway();
        switch (stateMachine.current()) {
            case PENDING -> stateMachine.transition(
                    ClusterPhase.PROVISIONING);
            case PROVISIONING -> {
                if (ready == spec.metadataReplicas()
                        + spec.storageReplicas()
                        + Math.max(1, current.readyGateway())) {
                    stateMachine.transition(ClusterPhase.READY);
                }
            }
            case READY -> {
                if (current.observedGeneration() < generation) {
                    stateMachine.transition(ClusterPhase.UPGRADING);
                }
            }
            case UPGRADING -> {
                if (current.observedGeneration() >= generation) {
                    stateMachine.transition(ClusterPhase.READY);
                }
            }
            default -> {
                // BACKING_UP / RESTORING / FAILED：由外部动作完成
            }
        }
    }

    /** 备份/恢复动作入口：READY → BACKING_UP / RESTORING。 */
    public void startBackup() {
        stateMachine.transition(ClusterPhase.BACKING_UP);
        actionSink.accept(new OperatorAction(
                OperatorAction.ActionType.TRIGGER_BACKUP, "cluster",
                "backup triggered"));
    }

    public void startRestore() {
        stateMachine.transition(ClusterPhase.RESTORING);
    }

    /** 运维完成：BACKING_UP / RESTORING / UPGRADING → READY。 */
    public void markReady() {
        if (stateMachine.current() == ClusterPhase.READY) {
            return;
        }
        stateMachine.transition(ClusterPhase.READY);
    }

    public void fail() {
        stateMachine.transition(ClusterPhase.FAILED);
    }

    public void retryFromFailed() {
        stateMachine.transition(ClusterPhase.PENDING);
    }
}
