package io.tieringkv.operator.k8s;

import io.tieringkv.operator.OperatorAction;

/** Operator 动作执行器（ADR-0322 M4 增强）：reconcile 输出 → 集群动作。 */
public interface ActionApplier {

    void apply(OperatorAction action);
}
