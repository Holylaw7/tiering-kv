package io.tieringkv.operator;

import java.util.List;
import java.util.function.Consumer;

/** Operator 控制器（ADR-0107）：reconcile 循环 → 计划 → 动作执行。 */
public final class TieringKVController {

    private final OperatorPlanner planner;
    private final Consumer<OperatorAction> actionSink;
    private long generation;

    public TieringKVController(Consumer<OperatorAction> actionSink) {
        this.planner = new OperatorPlanner();
        this.actionSink = actionSink;
    }

    public long applySpec(TieringKVClusterSpec spec,
                          TieringKVClusterStatus current) {
        generation++;
        List<OperatorAction> actions = planner.plan(spec, current,
                generation);
        for (OperatorAction action : actions) {
            actionSink.accept(action);
        }
        return generation;
    }
}
