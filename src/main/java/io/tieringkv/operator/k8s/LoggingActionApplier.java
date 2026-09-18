package io.tieringkv.operator.k8s;

import io.tieringkv.operator.OperatorAction;

/** Diagnostic action executor for dry-run and unit tests. */
public final class LoggingActionApplier implements ActionApplier {

    @Override
    public void apply(K8sTieringKVCluster resource, OperatorAction action) {
        String name = resource == null || resource.getMetadata() == null
                ? "<unknown>" : resource.getMetadata().getName();
        System.out.println("[operator] apply " + action.type()
                + " cluster=" + name
                + " target=" + action.target()
                + " detail=" + action.detail());
    }
}
