package io.tieringkv.operator;

import java.util.ArrayList;
import java.util.List;

/**
 * Operator 计划引擎（ADR-0107）：desired vs current → 动作列表。
 * 动作顺序：CREATE → REPLACE → SCALE → UPGRADE → BACKUP。
 */
public final class OperatorPlanner {

    public List<OperatorAction> plan(TieringKVClusterSpec spec,
                                     TieringKVClusterStatus current,
                                     long generation) {
        validate(spec);
        List<OperatorAction> actions = new ArrayList<>();
        if (current.readyMetadata() == 0 && current.readyStorage() == 0
                && current.readyGateway() == 0) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.CREATE, "cluster",
                    "create metadata/storage/gateway"));
        }
        if (current.readyMetadata() < spec.metadataReplicas()) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.SCALE_UP, "metadata",
                    "metadata replicas " + current.readyMetadata()
                            + " -> " + spec.metadataReplicas()));
        } else if (current.readyMetadata() > spec.metadataReplicas()) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.SCALE_DOWN, "metadata",
                    "metadata replicas " + current.readyMetadata()
                            + " -> " + spec.metadataReplicas()));
        }
        if (current.readyStorage() < spec.storageReplicas()) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.SCALE_UP, "storage",
                    "storage replicas " + current.readyStorage()
                            + " -> " + spec.storageReplicas()));
        } else if (current.readyStorage() > spec.storageReplicas()) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.SCALE_DOWN, "storage",
                    "storage replicas " + current.readyStorage()
                            + " -> " + spec.storageReplicas()));
        }
        if (current.observedGeneration() < generation) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.UPGRADE, "image",
                    "roll image to " + spec.image()));
        }
        if (spec.backupScheduleCron() != null
                && !spec.backupScheduleCron().isBlank()) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.TRIGGER_BACKUP, "backup",
                    "schedule " + spec.backupScheduleCron()));
        }
        if (actions.isEmpty()) {
            actions.add(new OperatorAction(
                    OperatorAction.ActionType.NOOP, "none",
                    "converged"));
        }
        actions.sort(null);
        return List.copyOf(actions);
    }

    public void validate(TieringKVClusterSpec spec) {
        if (spec.metadataReplicas() < 1 || spec.storageReplicas() < 1) {
            throw new IllegalArgumentException(
                    "replicas must be >= 1");
        }
        if (spec.regionIds().isEmpty()) {
            throw new IllegalArgumentException("region ids required");
        }
        if (spec.image() == null || spec.image().isBlank()) {
            throw new IllegalArgumentException("image required");
        }
        if (spec.metadataReplicas() % 2 == 0) {
            throw new IllegalArgumentException(
                    "metadata replicas must be odd (raft quorum)");
        }
    }
}
