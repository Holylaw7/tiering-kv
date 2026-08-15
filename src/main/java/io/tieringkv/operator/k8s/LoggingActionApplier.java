package io.tieringkv.operator.k8s;

import io.tieringkv.operator.OperatorAction;

/** 默认动作执行器：记录（生产可替换为 StatefulSet/Deployment 应用）。 */
public final class LoggingActionApplier implements ActionApplier {

    @Override
    public void apply(OperatorAction action) {
        System.out.println("[operator] apply " + action.type()
                + " target=" + action.target()
                + " detail=" + action.detail());
    }
}
