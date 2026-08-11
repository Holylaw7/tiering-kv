package io.tieringkv.operator;

/** Operator 动作（ADR-0107）：reconcile 输出，按优先级排序。 */
public record OperatorAction(ActionType type, String target,
                             String detail) implements
        Comparable<OperatorAction> {

    public enum ActionType {
        CREATE(0),
        REPLACE_NODE(1),
        SCALE_UP(2),
        SCALE_DOWN(3),
        UPGRADE(4),
        TRIGGER_BACKUP(5),
        NOOP(6);

        private final int priority;

        ActionType(int priority) {
            this.priority = priority;
        }
    }

    @Override
    public int compareTo(OperatorAction other) {
        return Integer.compare(type.priority, other.type.priority);
    }
}
