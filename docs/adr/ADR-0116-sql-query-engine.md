# ADR-0116: SQL Query Engine

## Status

Accepted

## Context

Phase 27 SQL 为只读子集（点查/范围/LIMIT）。需要 JOIN、聚合与可解释的
执行计划，但保持只读与 RBAC 约束。

## Decision

扩展 `sql/`：

1. Hash Join（两表等值连接）+ 聚合（COUNT/SUM/AVG/GROUP BY 子集）；
2. `ExplainPlan`：scan/filter/join/aggregate 节点树；
3. 谓词下推：WHERE key 条件推导为范围扫描；
4. 仅 READ 权限域可执行（RBAC 联动，ADR-0110）。

## Alternatives

1. 完整 SQL 引擎：超出 v1.1 窗口；
2. 不优化：JOIN 全量嵌套循环，性能不可用。

## Consequences

优点：可查询性与性能有基准支撑。

缺点：JOIN/聚合为内存执行，超大数据集需分布式（Phase 29）。

风险：GROUP BY 语义边界需严格测试。

## Implementation

代码影响范围：`sql/`（parser/planner/executor）+ 测试 +
`docs/sql/engine-design.md`。
