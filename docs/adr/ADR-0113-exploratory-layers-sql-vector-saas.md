# ADR-0113: Exploratory Layers — SQL, Vector, SaaS

## Status

Accepted（探索原型）

## Context

Phase 27 评估 SQL 查询层、向量检索与 SaaS 控制平面三个方向。为避免
过度承诺，三者均以 prototype/roadmap 交付，不宣称 GA。

## Decision

1. `sql/`：SELECT/WHERE/LIMIT 子集解析与执行（MVCC Snapshot Read），
   JOIN/聚合入路线图；
2. `vector/`：Embedding 存储 + 暴力检索基线，HNSW 为可选原型；
3. `saas/`：ClusterTenant + 配额校验原型，与 Operator 联动；
4. 三方向分别输出 roadmap，性能与召回率如实记录。

## Alternatives

1. 完整 SQL/向量引擎：范围过大，超出 v1.1 窗口；
2. 只写文档不写原型：无法验证可行性。

## Consequences

优点：可行性有代码证据；方向选择有数据支撑。

缺点：原型不代表生产承诺，需明确边界。

风险：范围蔓延，需按 roadmap 门控收敛。

## Implementation

代码影响范围：`sql/`、`vector/`、`saas/` + 测试 +
`docs/{sql,vector,saas}/roadmap.md`。
