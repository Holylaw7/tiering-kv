# 最终收尾报告（Project Closure）

## 1. 项目状态

- 版本：**v4.1.0 GA**（2026-08-16，GitHub Release + GHCR
  `ghcr.io/holylaw7/tiering-kv:v4.1.0` 已发布）；
- 全量回归：**约 6,736 个测试方法 / 14,950 次测试执行 /
  0 failures / 13 skipped**（Surefire 口径，JDK 21，benchmark 组
  由 release 门禁显式执行）；
- 真实 Runner 门禁：build / test（三分片）/ transaction-e2e /
  release（Benchmark + Trivy 0 漏洞 + 镜像 + Release）全绿；
- ROADMAP Phase 0–74 全部完成；P0–P4 优化路线图全部完成。

## 2. 里程碑

| 段 | 内容 | 状态 |
| --- | --- | --- |
| Phase 0–10 | 单机内核：RESP / MemTable / LFU-ARC / WAL / LSM / 迁移 / 并发 / mmap / Benchmark / 生产化 | ✅ |
| Phase 11–18 | 分布式：Raft / Multi-Raft / Region / 迁移 / Redis Cluster 网关 / 生产集成 | ✅ |
| Phase 19–24 | 事务：MVCC / Percolator 2PC / 网络化 / 云原生发布（v1.0） | ✅ |
| Phase 25–50 | 控制面 GA：Multi-Raft 网络化 / 真实 Runner 门禁 / 联邦一致性 / 工程基座 | ✅ |
| Phase 51–56 | v3.x：命令族补齐 / RESP3 / PubSub / Stream / GA 收口 | ✅ |
| Phase 57–62 | v4.0：向量 M1 / 多模型 M2 / 跨集群复制 M3 / 生产收口 | ✅ |
| P1–P2 | 技术债清偿 / 功能深度（BIT/GEO/JSON/TS/向量集合/跨集群 2PC/OBJECT/ACL/SCRIPT/RESP3） | ✅ |
| P3 | 混沌（真实磁盘/网络 netem）/ 可观测性收口 / W3C traceparent / CI 卫生 | ✅ |
| P4 + v4.1.0 | 多模块评估 / JDK 21 / 命令表驱动 / 发布门禁加固 / 依赖升级 | ✅ |

## 3. 能力终态

协议：RESP2/RESP3 完整类型、Pub/Sub、Redis Cluster 网关
（MOVED/ASK/ASKING/TRYAGAIN）、redis-cli 兼容。

存储：WAL（CRC + 并行恢复）、分段 SkipList MemTable（TTL）、SSTable
（Bloom/索引/mmap/BlockCache）、Leveled Compaction、冷热分层 +
自动 Flush/迁移 + 背压；崩溃恢复只读语义（真实 Runner 验证）。

分布式：16384 slot、持久化 Raft + 快照、Multi-Raft Region、
在线迁移、跨地域复制（水位/LWW/CRDT）、Key Sharding 异步执行、
异步响应保序（ResponseSequencer）。

事务：MVCC + HLC、Snapshot Read、Percolator 2PC、锁解析、
恢复、PITR/CDC、跨集群 2PC、TSO/全局时钟。

多模型：JSON 路径、时序、向量（HNSW + 集合 + 索引文件）、SQL
（join/聚合/分片/物化视图）。

可观测性：INFO sections ×7、Prometheus 端点、W3C traceparent、
JFR 管线、向量/备份/复制水位。

云原生与安全：Kubernetes Operator、备份恢复（含向量索引）、滚动升级、
优雅停机、容量模型、RBAC/mTLS/HMAC/密钥轮换、合规证明。

## 4. 质量与工程数据

- 测试：约 6,736 个测试方法 / 14,950 次测试执行（单元/集成/压力/
  混沌），Phase 19 起逐阶段真实 Runner 门禁；
- ADR：0001–0348（存储/网络/锁/IO/淘汰/序列化/一致性/性能全覆盖）；
- 工程：JDK 21、命令表驱动、包边界固化、三分片 CI + JFR、
  Conventional Commits、main/develop 双分支；
- 发布门禁真实 Runner 修复链（v4.1.0）：单 job 挂死 → 分片 + 超时 →
  自检适配 → 依赖漏洞升级 → 分片均衡，共 4 轮并全部归档。

## 5. 技术债终态

已关闭：TD-001/002/005…051（含 TD-015 经 ADR-0349 处置关闭，
P3 收口新增 TD-038/051）。

已关闭补充：TD-046/TD-049（ADR-0350 容器级 disk-full/readonly 注入，
loop 设备 bind 为 txn-meta /data + 真实网关失败/恢复断言；slow io
在无 device-mapper 的托管 Runner 显式 SKIPPED，特权 Runner 按需启用）。

## 6. 维护指南

- 日常：`develop` 集成，`main` 发布；全量回归 + 真实 Runner 门禁；
- 发布：打 `vX.Y.Z` tag（release workflow 自动跑分片测试 + Benchmark
  + Trivy + GHCR + Release）；
- 新增命令：改 `CommandCatalog`（默认表冻结 129 + 动态 3 = 132）；
- 新增包：遵循 PackageBoundaryTest（protocol 零依赖、主链单向）；
- 依赖升级：升级后跑 operator 相关测试（fabric8/okhttp 兼容）与全量；
- CI 分片：`scripts/shard-tests.sh <index> 3`（重型包 0/1、轻量 2）。

## 7. 结论

Tiering-KV 已从零完成一个具有工程完整性的 Redis 兼容冷热分层分布式
KV 存储系统，单机协议/存储、分布式共识/事务、多模型、云原生与
可观测性闭环齐备，并全部通过真实 GitHub Runner 门禁。
开发阶段正式完结，进入维护模式。
