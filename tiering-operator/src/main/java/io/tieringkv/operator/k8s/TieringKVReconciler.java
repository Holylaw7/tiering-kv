package io.tieringkv.operator.k8s;

import io.tieringkv.operator.ClusterPhase;
import io.tieringkv.operator.ClusterStateMachine;
import io.tieringkv.operator.OperatorAction;
import io.tieringkv.operator.OperatorPlanner;
import io.tieringkv.operator.TieringKVClusterSpec;
import io.tieringkv.operator.TieringKVClusterStatus;

import java.util.ArrayList;
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

        if (resource.getMetadata() != null
                && resource.getMetadata().getDeletionTimestamp() != null) {
            K8sTieringKVClusterStatus deleting =
                    new K8sTieringKVClusterStatus();
            deleting.setReadyMetadata(k8sStatus.getReadyMetadata());
            deleting.setReadyStorage(k8sStatus.getReadyStorage());
            deleting.setReadyGateway(k8sStatus.getReadyGateway());
            deleting.setObservedGeneration(k8sStatus.getObservedGeneration());
            deleting.setLastAction("DELETE:cluster");
            deleting.setPhase("DELETING");
            return new ReconcileResult(List.of(new OperatorAction(
                    OperatorAction.ActionType.DELETE, "cluster",
                    "delete owned workloads and services")), deleting);
        }

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
        int expectedGateway = k8sSpec.effectiveGatewayReplicas();
        advance(machine, spec, current, generation, expectedGateway);

        List<OperatorAction> actions = new ArrayList<>(
                planner.plan(spec, current, generation));
        String backupRequestId = backupRequestId(resource);
        if (backupRequestId != null
                && !K8sActionApplier.backupJobName(resource, backupRequestId)
                        .equals(k8sStatus.getLastBackupJob())) {
            boolean hasBackupAction = actions.stream().anyMatch(
                    a -> a.type() == OperatorAction.ActionType.TRIGGER_BACKUP);
            if (!hasBackupAction) {
                actions.removeIf(
                        a -> a.type() == OperatorAction.ActionType.NOOP);
                actions.add(new OperatorAction(
                        OperatorAction.ActionType.TRIGGER_BACKUP,
                        "backup:" + backupRequestId,
                        "on-demand backup request " + backupRequestId));
            }
        }
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
        updated.setLastBackupJob(k8sStatus.getLastBackupJob());
        updated.setLastBackupStatus(k8sStatus.getLastBackupStatus());
        updated.setLastBackupTime(k8sStatus.getLastBackupTime());
        updated.setMessage(k8sStatus.getMessage());
        return new ReconcileResult(List.copyOf(actions), updated);
    }

    private static String backupRequestId(K8sTieringKVCluster resource) {
        if (resource.getMetadata() == null
                || resource.getMetadata().getAnnotations() == null) {
            return null;
        }
        String raw = resource.getMetadata().getAnnotations()
                .get(K8sActionApplier.BACKUP_REQUEST_ANNOTATION);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static void advance(ClusterStateMachine machine,
                                TieringKVClusterSpec spec,
                                TieringKVClusterStatus current,
                                long generation,
                                int expectedGateway) {
        boolean replicasReady = current.readyMetadata() == spec.metadataReplicas()
                && current.readyStorage() == spec.storageReplicas()
                && current.readyGateway() >= expectedGateway;
        switch (machine.current()) {
            case PENDING -> machine.transition(
                    ClusterPhase.PROVISIONING);
            case PROVISIONING -> {
                if (replicasReady) {
                    machine.transition(ClusterPhase.READY);
                }
            }
            case READY -> {
                if (current.observedGeneration() < generation
                        || !replicasReady) {
                    machine.transition(ClusterPhase.UPGRADING);
                }
            }
            case UPGRADING -> {
                if (current.observedGeneration() >= generation
                        && replicasReady) {
                    machine.transition(ClusterPhase.READY);
                }
            }
            default -> {
                // BACKING_UP / RESTORING / FAILED：外部动作完成
            }
        }
    }
}
