package io.tieringkv.operator.k8s;

import io.tieringkv.operator.OperatorAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Diagnostic action executor for dry-run and unit tests. */
public final class LoggingActionApplier implements ActionApplier {

    private static final Logger log = LoggerFactory.getLogger(LoggingActionApplier.class);

    @Override
    public void apply(K8sTieringKVCluster resource, OperatorAction action) {
        String name = resource == null || resource.getMetadata() == null
                ? "<unknown>" : resource.getMetadata().getName();
        log.info("[operator] apply {} cluster={} target={} detail={}",
                action.type(), name, action.target(), action.detail());
    }
}
