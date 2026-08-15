package io.tieringkv.operator.k8s;

import io.tieringkv.operator.ClusterPhase;
import io.tieringkv.operator.ClusterStateMachine;
import io.tieringkv.operator.OperatorAction;
import io.tieringkv.operator.OperatorPlanner;
import io.tieringkv.operator.TieringKVClusterSpec;
import io.tieringkv.operator.TieringKVClusterStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K8s reconcile 核心（ADR-0322 M4 增强）：CRD spec/status ↔ 内部模型，
 * 复用 OperatorPlanner + ClusterStateMachine，输出动作与更新后的状态。
 * 纯逻辑可单测，不依赖真实集群。
 */
public final class TieringKVReconciler {

    private final OperatorPlanner planner = new OperatorPlanner();
    private final Map<String, ClusterStateMachine> machines =
            new ConcurrentHashMap<>();

    public record ReconcileResult(
            List<OperatorAction> actions,
            K8sTieringKVClusterStatus status) {
    }

    public ReconcileResult reconcile(K8sTieringKVCluster resource) {
        if (resource == null || resource.getSpec() == null) {
            throw new IllegalArgumentException(
                    "resource and spec required");
        }
        K8sTieringKVClusterSpec k8sSpec = resource.getSpec();
        K8sTieringKVClusterStatus k8sStatus =
                resource.getStatus() == null
                        ? new K8sTieringKVClusterStatus()
                        : resource.getStatus();
        long generation = resource.getMetadata() == null
                || resource.getMetadata().getGeneration() == null
                ? 0 : resource.getMetadata().getGeneration();
        String name = resource.getMetadata() == null
                || resource.getMetadata().getName() == null
                ? "default" : resource.getMetadata().getName();

        TieringKVClusterSpec spec = new TieringKVClusterSpec(
                k8sSpec.getMetadataReplicas(),
                k8sSpec.getStorageReplicas(),
                k8sSpec.getRegionIds(),
                k8sSpec.getImage(),
                k8sSpec.getBackupScheduleCron(),
                k8sSpec.getBackupRetentionHours());
        TieringKVClusterStatus current = new TieringKVClusterStatus(
                k8sStatus.getReadyMetadata(),
                k8sStatus.getReadyStorage(),
                k8sStatus.getReadyGateway(),
                k8sStatus.getObservedGeneration(),
                k8sStatus.getLastAction());

        ClusterStateMachine machine = machines.computeIfAbsent(
                name,
                ignored -> new ClusterStateMachine(
                        ClusterPhase.PENDING));
        advance(machine, spec, current, generation);

        List<OperatorAction> actions = planner.plan(spec, current,
                generation);
        String lastAction = actions.isEmpty() ? "noop"
                : actions.get(0).type().name();
        for (OperatorAction action : actions) {
            if (action.type() == OperatorAction.ActionType.NOOP) {
                continue;
            }
            lastAction = action.type() + ":" + action.target();
        }

        K8sTieringKVClusterStatus updated =
                new K8sTieringKVClusterStatus();
        updated.setReadyMetadata(k8sStatus.getReadyMetadata());
        updated.setReadyStorage(k8sStatus.getReadyStorage());
        updated.setReadyGateway(k8sStatus.getReadyGateway());
        updated.setObservedGeneration(generation);
        updated.setLastAction(lastAction);
        updated.setPhase(machine.current().name());
        return new ReconcileResult(actions, updated);
    }

    private static void advance(ClusterStateMachine machine,
                                TieringKVClusterSpec spec,
                                TieringKVClusterStatus current,
                                long generation) {
        int ready = current.readyMetadata() + current.readyStorage()
                + current.readyGateway();
        switch (machine.current()) {
            case PENDING -> machine.transition(
                    ClusterPhase.PROVISIONING);
            case PROVISIONING -> {
                int expected = spec.metadataReplicas()
                        + spec.storageReplicas()
                        + Math.max(1, current.readyGateway());
                if (ready == expected) {
                    machine.transition(ClusterPhase.READY);
                }
            }
            case READY -> {
                if (current.observedGeneration() < generation) {
                    machine.transition(ClusterPhase.UPGRADING);
                }
            }
            case UPGRADING -> {
                if (current.observedGeneration() >= generation) {
                    machine.transition(ClusterPhase.READY);
                }
            }
            default -> {
                // BACKING_UP / RESTORING / FAILED：外部动作完成
            }
        }
    }
}
