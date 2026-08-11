package io.tieringkv.sql;

import java.util.List;

/** 执行计划（ADR-0116）：节点序列。 */
public record ExplainPlan(List<PlanNode> nodes) {

    public enum NodeType {
        SCAN,
        FILTER,
        JOIN,
        AGGREGATE
    }

    public record PlanNode(NodeType type, String detail) {
    }

    public ExplainPlan {
        nodes = List.copyOf(nodes);
    }
}
