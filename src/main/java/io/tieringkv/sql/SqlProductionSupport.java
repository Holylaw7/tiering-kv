package io.tieringkv.sql;

import java.util.Locale;

/**
 * SQL 生产化支持（ADR-0295）：统一错误码 + EXPLAIN 完整计划树。
 */
public final class SqlProductionSupport {

    public record SqlError(String code, String message) {
    }

    private SqlProductionSupport() {
    }

    /** 错误归一化：语法 / 未知列 / 类型 / 内部。 */
    public static SqlError errorOf(Throwable error) {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("syntax")
                || lower.contains("parse")) {
            return new SqlError("SYNTAX_ERROR", message);
        }
        if (lower.contains("type")
                || lower.contains("cannot cast")) {
            return new SqlError("TYPE_ERROR", message);
        }
        if (lower.contains("column")
                || lower.contains("unknown field")
                || lower.contains("not found")) {
            return new SqlError("UNKNOWN_COLUMN", message);
        }
        return new SqlError("INTERNAL_ERROR", message);
    }

    /** EXPLAIN 计划树：节点编号 + 类型 + 详情 + 下推摘要。 */
    public static String explain(ExplainPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("EXPLAIN PLAN").append(System.lineSeparator());
        for (int i = 0; i < plan.nodes().size(); i++) {
            ExplainPlan.PlanNode node = plan.nodes().get(i);
            builder.append("  ").append(i).append(": ")
                    .append(node.type()).append(' ')
                    .append(node.detail())
                    .append(System.lineSeparator());
        }
        long scans = plan.nodes().stream()
                .filter(node -> node.type()
                        == ExplainPlan.NodeType.SCAN)
                .count();
        long joins = plan.nodes().stream()
                .filter(node -> node.type()
                        == ExplainPlan.NodeType.JOIN)
                .count();
        long aggregates = plan.nodes().stream()
                .filter(node -> node.type()
                        == ExplainPlan.NodeType.AGGREGATE)
                .count();
        builder.append("summary: scans=").append(scans)
                .append(", joins=").append(joins)
                .append(", aggregates=").append(aggregates)
                .append(", pushdown=available")
                .append(System.lineSeparator());
        return builder.toString();
    }
}
