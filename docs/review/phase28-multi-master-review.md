# Phase 28 评审报告：Multi-Master Replication & Advanced Query Engines

Phase 28 · 2026-08-11 · v1.1.0

## 1. 结论

Phase 28 完成多主复制与高级查询引擎：

- **双向复制 + CRDT**（ADR-0114）：VersionVector 环回抑制，LWW/
  GCounter/GSet/OrSet 收敛；
- **两地三中心容灾**（ADR-0115）：拓扑 + 计划/故障切换 + 演练；
- **SQL 引擎**（ADR-0116）：Hash Join、聚合、GROUP BY、ExplainPlan；
- **HNSW + 混合检索**（ADR-0117）：层级索引 + 标量过滤；
- **SaaS 多租户**（ADR-0118）：注册/审计/配额/集群生成；
- **RPC 帧级令牌**（ADR-0119）：信封 v1 兼容旧帧。

新增 **251 项测试**，全量 **3216/3216 PASS**（目标 ≥3200 ✅；另 6 项
容器门控本地跳过），单向路径零回退。

## 2. ADR

| ADR | 主题 |
| --- | --- |
| 0114 | Bidirectional Replication and CRDT |
| 0115 | Disaster Recovery Topology |
| 0116 | SQL Query Engine |
| 0117 | HNSW and Hybrid Search |
| 0118 | SaaS Multi-Tenant Control Plane |
| 0119 | RPC Frame Token and v1.1 Release |

## 3. 关键修复

1. HNSW 多层重复条目去重（同分平局取分数等价）；
2. groupBy 键改为字符串（byte[] 身份比较缺陷）；
3. 全量负载下稳定化：提案超时 5s→15s、元数据客户端重试 2→5 轮、
   混沌收敛等待放宽、基准门控设防抖下限；
4. RPC 异步路径 SecurityException → ERROR 帧（不关闭连接）。

## 4. 基准（进程内口径）

| 指标 | 结果 |
| --- | --- |
| CRDT 合并 | 1–2.5M ops/s |
| 双向写 | 33–167K ops/s |
| SQL JOIN 1K×1K | 1–5ms |
| HNSW 100×topK5 | ≈38ms |
| DR RTO（进程内） | ≈1ms |

## 5. 局限（不隐藏）

1. 跨地域 RTT/RTO/RPO 待 CI/裸机；
2. HNSW 原型级（参数未校准）；SQL 内存执行；
3. SaaS 隔离需 K8s 配合；
4. 分布式 SQL、向量分片、三地五中心为 Phase 29。

## 6. 下一步

- v1.1.0 发布执行（release.yml）；
- Phase 29：分布式 SQL、向量分片、Geo CRDT 大规模验证。
