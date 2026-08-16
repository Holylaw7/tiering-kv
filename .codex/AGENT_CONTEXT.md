# AGENT_CONTEXT — 项目长期上下文

> 每次会话开始时阅读本文档与仓库状态，快速恢复上下文。

## 1. 项目概况

Tiering-KV（Mini Redis）：Redis 协议兼容的 LSM 冷热分层分布式 KV
存储引擎，从零实现并完成工程化交付。

- 当前版本：**v4.1.0 GA**（2026-08-16 发布，GHCR
  `ghcr.io/holylaw7/tiering-kv:v4.1.0`）；
- 阶段状态：ROADMAP Phase 0–74 全部完成，P0–P4 优化路线图全部完成；
- 测试：全量 **约 6,738 个测试方法 / 14,957 次测试执行 /
  0 failures / 13 skipped**（Surefire 口径，JDK 21）；
- 门禁：build / test（三分片）/ transaction-e2e / release
  （Benchmark + Trivy 0 漏洞 + 镜像 + GitHub Release）全绿；
- 技术栈：Java 21、Netty、Jackson、fabric8 6.13.4、JUnit 5、JMH、
  GitHub Actions。

## 2. 能力终态

- 协议：RESP2/RESP3 完整类型、Pub/Sub、Redis Cluster 网关
  （MOVED/ASK/ASKING/TRYAGAIN）、redis-cli 兼容；
- 存储：WAL（CRC + 并行恢复）、分段 SkipList MemTable（TTL）、
  SSTable（Bloom/索引/mmap/BlockCache）、Leveled Compaction、
  冷热分层 + 自动 Flush/迁移 + 背压；
- 缓存：LFU（衰减 + 索引）、ARC（byte 口径）、Segment LFU + Async
  Buffer、Hot Key 缓存 + Request Coalescing；
- 分布式：16384 slot、持久化 Raft + 快照、Multi-Raft Region、
  在线迁移、跨地域复制（水位/LWW/CRDT）、Key Sharding 异步执行；
- 事务：MVCC + HLC、Snapshot Read、Percolator 2PC、锁解析、恢复、
  PITR/CDC、跨集群 2PC；
- 多模型：JSON 路径、时序、向量（HNSW + 集合 + 索引文件）、SQL；
- 可观测性：INFO sections ×7、Prometheus 端点、W3C traceparent、
  JFR 管线、向量/备份/复制水位；
- 云原生与安全：Kubernetes Operator、备份恢复（含向量索引）、
  滚动升级、优雅停机、容量模型、RBAC/mTLS/HMAC/密钥轮换。

## 3. 工程约定

- 流程：需求 → 设计 → ADR → TDD → 全量回归 → 真实 Runner 门禁 →
  Conventional Commit；
- 分支：`main`（发布）/ `develop`（集成）/ `feature/*`；
- 命令注册：`CommandCatalog`（默认 129 + 动态 info/exec/command =
  132 冻结）；
- 包边界：`PackageBoundaryTest`（protocol 零依赖、主链单向）；
- CI 分片：`scripts/shard-tests.sh <index> 3`（重型包 0/1、轻量 2）；
- 本地全量：`mvn '-Dsurefire.excludedGroups=benchmark' test`
  （PowerShell 需引号）；benchmark 组由 release 门禁显式执行；
- 发布：打 `vX.Y.Z` tag 触发 release workflow。

## 4. 常用入口

- 路线图：ROADMAP.md；变更：CHANGELOG.md；优化规划：
  docs/planning/optimization-roadmap.md；
- 最终收尾报告：docs/review/final-project-closure.md；
- ADR：docs/adr/（0001–0348）；任务：.codex/tasks/；
- 配置：config/application.yaml；部署：deploy/。
