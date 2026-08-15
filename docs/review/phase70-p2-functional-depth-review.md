# Phase 70 Review — P2 Functional Depth（Phase 66–70 归档）

## 总体结论

Optimization Roadmap P2 功能深度主体完成：BIT/GEO、JSON 路径、时序、
向量多集合、跨集群 2PC 五项交付。全量回归 **14845 tests / 0
failures / 6 skipped**（本地），真实 Runner 门禁 6/6 全绿
（main/develop × build/test/transaction-e2e）。

## 交付清单

| Phase | 交付 | ADR | 关键点 |
| --- | --- | --- | --- |
| 66 | BIT/GEO 命令族 | 0334/0335 | 位图即字符串 + BYTE/BIT 范围；ZSET+52 位 geohash，Redis 文档基准（sqc8b49rny0 / 166274.1516m）通过 |
| 67 | JSON 路径命令族 | 0336 | jackson-databind + 自研路径子集；SET NX/XX + 中间对象创建；变更原子 + TTL 保留 |
| 68 | 时序命令族 | 0337 | TS.RANGE 桶聚合（floorDiv 对齐）、INCRBY 原子累加、MRANGE、REDUCE |
| 69 | 向量多集合命名空间 | 0338 | 集合隔离 + 自动 checkpoint + loadAll；COLLECTION 前缀 + LIST/DROP/CHECKPOINT；SQL 混合检索集合接线 |
| 70 | 跨集群 2PC | 0339 | TXN_PREPARE/COMMIT/ROLLBACK 阶段事件；决策先行（携带 mutations）；LWW 收敛；recover 幂等 |

## 测试与门禁

- 新增测试 89 项（BIT 13 + GEO 14 + JSON 22 + TS 18 + 向量集合 14 +
  跨集群 2PC 18，部分计数含既有扩展）；
- 全量回归 14845 / 0 failures / 6 skipped；
- 真实 Runner：三次提交各 6/6；期间命中 2 次已知 Raft flaky
  （MultiRaftTransportTest / ChaosValidationTest），标准重跑后通过，
  无真实缺陷；
- 关键缺陷修复记录：GEO 位序对齐 Redis（lat 偶位）、JSON.GET
  JSONPath 返回序列化数组文本、NUMINCRBY 浮点显示精度、参与者
  双重 resolver.accept 导致提交不落盘。

## 已知限制（如实记录）

- GEO STORE/STOREDIST/GEOSEARCHSTORE 暂缓；JSON SET/DEL 通配路径
  暂缓；TS.MRANGE 无标签过滤、TS.REDUCE 为扩展；向量集合为内存
  索引（SEARCH 未接 HNSW）；跨集群 2PC 为暂存式（无锁）且决策日志
  单机文件；
- P2 剩余：OBJECT/SCRIPT/ACL 命令族与 RESP3 完整类型（下一交付）。

## 后续

- P2 剩余项按 optimization-roadmap 继续（OBJECT/SCRIPT/ACL →
  RESP3 完整类型）；
- P2 全部完成后进入 P3（混沌/可观测性：真实磁盘故障、netem、
  Metrics/OTel、备份恢复纳入向量与复制水位）。
