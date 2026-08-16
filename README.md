# Tiering-KV

> Redis 协议兼容的 LSM 冷热分层 KV 存储引擎（Mini Redis）——
> 内存/磁盘分层 + 分布式事务 + 多模型值 + 向量检索 + SQL + 云原生运行时。

**版本：v4.1.0 GA（2026-08-16）** · 全量测试 **14919 / 0 failures** ·
JDK 21 · 真实 GitHub Runner 门禁 7/7（build / test（三分片）/ transaction-e2e /
release：Benchmark + Trivy 0 漏洞 + GHCR 镜像）

## 项目定位

一个从零实现、具有工程完整性的分布式 KV 存储系统：单机侧对齐 Redis
协议与冷热分层存储（WAL → MemTable → SSTable → Compaction），分布式
侧对齐 Raft/Multi-Raft 集群、Region 生命周期、跨地域复制与 Percolator
风格分布式事务；在此之上提供 JSON / 时序 / 向量 / SQL 多模型能力与
云原生（Kubernetes Operator、备份恢复、PITR、CDC）生产闭环。

## 快速开始

前置：JDK 21 + Maven 3.9。

```bash
# 全量测试（benchmark 组由 release 门禁显式执行）
mvn -Dsurefire.excludedGroups=benchmark test

# 启动单机服务（默认 0.0.0.0:6379，配置见 config/application.yaml）
mvn exec:java -Dexec.mainClass=io.tieringkv.Main
```

```bash
# redis-cli 验证
redis-cli -p 6379 PING
redis-cli -p 6379 SET k v
redis-cli -p 6379 GET k
redis-cli -p 6379 INFO
redis-cli -p 6379 INFO vector        # 可观测性 sections：vector/replication/multimodel/backup/tracing
```

分布式事务栈（coordinator/participant/metadata/gateway 五容器）：

```bash
docker compose -f deploy/docker-compose.transaction.yml up -d --wait
redis-cli -p 6379 SET k v   # 自动 2PC 事务
```

Kubernetes：`deploy/kubernetes/*.yaml` + Operator（fabric8 6.13.4）。

## 能力矩阵

| 域 | 能力 |
| --- | --- |
| 协议 | RESP2/RESP3 完整类型、Pub/Sub、Redis Cluster 网关（MOVED/ASK/TRYAGAIN + ASKING）、redis-cli 兼容 |
| 存储 | WAL（CRC32C + 崩溃恢复 + 并行恢复）、MemTable（分段 SkipList + TTL）、SSTable（Bloom/索引/mmap/BlockCache）、Leveled Compaction、冷热分层 + 自动 Flush/迁移 + 背压 |
| 缓存 | LFU（衰减 + TreeSet 索引）、ARC（byte 口径）、Segment LFU + Async Buffer、Hot Key 缓存 + Request Coalescing |
| 分布式 | 16384 hash slot、Raft（持久化日志/快照/选举/转移）、Multi-Raft Region、在线迁移、跨地域复制（批量 + 水位 + LWW/CRDT）、Key Sharding 异步执行 |
| 事务 | MVCC + HLC、Snapshot Read、Percolator 2PC（Prewrite/Commit/Rollback）、锁解析、事务恢复、PITR/CDC、跨集群 2PC |
| 多模型 | JSON 路径（JSON.SET/GET/...）、时序（TS.ADD/RANGE/INCRBY/...）、VECTOR 值 + HNSW 检索、SQL 引擎（join/聚合/分片执行） |
| 可观测性 | INFO sections（server/cluster/txn/mvcc/vector/replication/multimodel/backup/tracing）、Prometheus 文本端点（/metrics/prometheus）、W3C traceparent 透传、JFR 管线 |
| 安全 | RBAC/ACL、mTLS + HMAC 签名 RPC、密钥轮换、证书生命周期、审计/合规证明 |
| 云原生 | Kubernetes Operator（fabric8 reconcile + CRD 状态）、备份/恢复（含向量索引与复制水位）、滚动升级、优雅停机、容量模型 |
| 工程 | JDK 21、命令表驱动（CommandCatalog）、包边界固化（PackageBoundaryTest）、三分片 CI + JFR、Conventional Commits、ADR 全记录 |

## 性能基线（摘要，详见 docs/benchmark/）

| 指标 | 数值 |
| --- | --- |
| 存储层 GET（1M 数据集） | P99 ≈ 2.5μs |
| 网络端到端 GET | P99 ≈ 0.19ms |
| HNSW 检索（20K×64） | P50 0.473ms / P99 0.847ms（旧暴力 9.9ms） |
| 并行迁移（100B） | 209MB/s |
| 虚拟线程服务（JDK 21 POC） | 941K ops/s |
| WAL append（buffered） | 1.44M ops/s |
| 冷层 mmap 随机读 | P99 0.012–0.048ms（page-cache 热口径） |

## 发布历史

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v3.7.0 | 2026-08-14 | GA 收口（真实 Runner 门禁 7/7） |
| v3.7.1 | 2026-08-14 | 维护补丁（依赖漏洞/CI 稳定化） |
| v4.0.0-rc1 | 2026-08-14 | 向量存储 M1 + 多模型 M2 + 跨集群复制 M3 |
| v4.1.0 | 2026-08-16 | P4 工程现代化（JDK 21/命令目录）+ 可观测性收口 + 依赖升级 + 发布门禁加固 |

## 目录与文档

```text
src/main/java/io/tieringkv/   协议/存储/集群/事务/多模型/云原生实现
src/test/java/io/tieringkv/   14919 项测试（单元/集成/压力/混沌）
docs/adr/                     ADR-0001 ~ ADR-0348（架构决策记录）
docs/requirements/             需求与验收
docs/architecture/            总体/存储/网络/并发架构
docs/design/                  详细设计
docs/benchmark/               性能报告
docs/review/                  阶段评审与最终收尾
docs/planning/                P0–P4 优化路线图
.codex/tasks/                 各 Phase 任务与状态
deploy/                       Docker/K8s/Operator 产物
scripts/                      build/benchmark/stress/混沌/发布脚本
docs/project-history.md       Phase 0–54 逐阶段演进历史（旧 README 归档）
```

更多：需求见 [docs/requirements/requirements.md](docs/requirements/requirements.md)，
架构见 [docs/architecture/overview.md](docs/architecture/overview.md)，
路线图见 [ROADMAP.md](ROADMAP.md)，变更见 [CHANGELOG.md](CHANGELOG.md)，
历史演进见 [docs/project-history.md](docs/project-history.md)，
最终收尾报告见 [docs/review/final-project-closure.md](docs/review/final-project-closure.md)。

## 开发与贡献

- 流程：需求 → 设计 → ADR → TDD → 全量回归 → 真实 Runner 门禁 → Conventional Commit；
- 分支：`main`（发布）/ `develop`（集成）/ `feature/*`；
- 本地全量：`mvn '-Dsurefire.excludedGroups=benchmark' test`；
- 发布：打 `vX.Y.Z` tag 触发 release workflow（分片测试 + Benchmark + Trivy + GHCR + GitHub Release）。

参见 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [LICENSE](LICENSE)。
