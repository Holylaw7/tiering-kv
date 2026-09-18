package io.tieringkv.operator.k8s;

import io.tieringkv.operator.OperatorAction;

/** Operator action executor: reconcile output to Kubernetes resources. */
public interface ActionApplier {

    /** Apply one action against the CR that produced it. */
    void apply(K8sTieringKVCluster resource, OperatorAction action);

    /**
     * Read the current workload status before reconcile. Implementations that
     * do not own Kubernetes resources may return {@code null}.
     */
    default K8sTieringKVClusterStatus observe(
            K8sTieringKVCluster resource) {
        return null;
    }
}
