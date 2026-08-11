# Phase 27 评审报告：Multi-Region Replication & Enterprise Integration

Phase 27 · 2026-08-11 · v1.1.0 方向

## 1. 结论

Phase 27 完成跨地域与企业集成闭环：

- **Multi-Region Replication**（ADR-0108）：CDC 事件载体 + async/sync +
  滞后跟踪 + 冲突标记；
- **Geo Distributed Transaction**（ADR-0109）：决策日志先行 + 远程
  participant + 幂等恢复；
- **RBAC 网关/RPC 接线**（ADR-0110）：AUTH 会话 + 命令/RPC 权限守卫；
- **PITR 保留策略**（ADR-0111）：安全水位删除；
- **CDC 多消费者组**（ADR-0112）：组间进度隔离；
- **SQL/Vector/SaaS 探索原型**（ADR-0113）：路线图 + 基准。

新增 **264 项测试**，全量 **2965/2965 PASS**（目标 ≥2950 ✅；另 6 项
容器门控本地跳过），单地域零回退。

## 2. ADR

| ADR | 主题 |
| --- | --- |
| 0108 | Multi-Region Replication |
| 0109 | Geo Distributed Transaction |
| 0110 | RBAC Gateway and RPC Integration |
| 0111 | PITR Retention and Archive Lifecycle |
| 0112 | CDC Multi-Consumer Groups |
| 0113 | Exploratory Layers: SQL, Vector, SaaS |

## 3. 关键实现与修复

1. 复制管道复用 CDC 事件，ASYNC 即投即确认、SYNC 全 ack + 超时；
2. Geo 协调器按地域分组批量 prewrite/commit（修复逐 mutation 提交触发
   participant 状态机提前 COMMITTED 的缺陷）；
3. 单调时钟保证 startTS < commitTS（修复 provisional 删除误删同时间戳
   已提交版本）；
4. CDC 消费者组独立 checkpoint；PITR 保留保护安全水位；
5. SQL 解析支持 `>=` 后接 LIMIT；向量检索跳过空/全零向量。

## 4. 基准（进程内口径，如实记录）

| 指标 | 结果 |
| --- | --- |
| SYNC 复制 | 100–250K ops/s |
| PITR append | 2.9–4.2K ops/s |
| CDC fan-out | 1.7–2.4K ops/s |
| RBAC 校验 | 1–10M ops/s |
| SQL 点查 | 0.36–0.5M ops/s |
| Vector topK | 5.5–14.5K ops/s |

## 5. 局限（不隐藏）

1. 复制/Geo 为进程内等价，跨地域 RTT 未计入（CI/裸机待执行）；
2. RBAC 的 RPC 帧级令牌传输待协议扩展（v1 兼容评审）；
3. SQL/Vector/SaaS 为探索原型，不宣称 GA；
4. 双向复制、CRDT、两地三中心为 Phase 28。

## 6. 下一步

- v1.1.0 冻结与发布流水线执行；
- 双向复制/CRDT、完整 SQL、HNSW 生产化、SaaS 多租户。
