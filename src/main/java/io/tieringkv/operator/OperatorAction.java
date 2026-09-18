package io.tieringkv.operator;

/** Operator 动作（ADR-0107）：reconcile 输出，按优先级排序。 */
public record OperatorAction(ActionType type, String target,
                             String detail) implements
        Comparable<OperatorAction> {

    public enum ActionType {
        DELETE(0),
        CREATE(1),
        REPLACE_NODE(2),
        SCALE_UP(3),
        SCALE_DOWN(4),
        UPGRADE(5),
        TRIGGER_BACKUP(6),
        NOOP(7);

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
