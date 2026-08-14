package io.tieringkv.operator;

/** 集群生命周期阶段（ADR-0322）：reconcile 状态机节点。 */
public enum ClusterPhase {
    PENDING,
    PROVISIONING,
    READY,
    UPGRADING,
    BACKING_UP,
    RESTORING,
    FAILED
}
