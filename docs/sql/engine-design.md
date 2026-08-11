# SQL 引擎设计

Phase 28 · ADR-0116

## 能力

- Hash Join（等值连接，内存执行）；
- 聚合：COUNT / SUM / AVG；
- GROUP BY（按 key 子集）；
- ExplainPlan：SCAN / FILTER / JOIN / AGGREGATE 节点；
- 只读 + RBAC（READ 权限域）约束。

## 基准（进程内）

- JOIN 1K×1K ≈1–5ms；聚合 10K 行 ≈10ms；
- 点查 0.36–0.5M ops/s（Phase 27 口径延续）。

## 限制

- 内存执行，超大数据集需分布式（Phase 29）；
- GROUP BY 语义边界严格测试覆盖。
