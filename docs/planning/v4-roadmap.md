# v4.0 Roadmap

## 方向

- 多模型：SQL/向量生产化、JSON/时序；
- 多集群：联邦一致性、跨地域多活深化；
- 云原生：Operator 完整化、多云部署、SaaS 深化；
- 性能：真实 Runner 基线、Jepsen 外部化分区注入。

## 流程

RFC（docs/planning/rfc-template.md）→ 评审 → ADR → 分支开发。

## 当前

- RFC-0001（docs/planning/rfc-0001-v4-multi-model.md）：**Approved**
  （2026-08-14）→ ADR-0318 Accepted；
- `feature/v4-multi-model` 分支已创建；
- 阶段一（SQL 索引接线）已交付：`SqlIndexRegistry` +
  `IndexAwarePlanner`（含索引选择/拒绝路径）。

## v3.7.1（维护补丁，v4.0 前置）

维护模式首个补丁候选，范围限定为真实 GitHub Runner 门禁暴露的修复，
不引入新功能：

1. 真实 Runner 门禁全绿（build/test/transaction-e2e/release，7/7 ×2）；
2. GHCR 镜像命名修正（`ghcr.io/holylaw7/tiering-kv`，owner 全小写）；
3. 依赖漏洞修复（netty 4.1.136.Final / slf4j 2.0.17 / logback 1.5.34，
   Trivy 0 漏洞）；
4. 容器入口契约统一（事务/kind 显式 TxnRuntimeMain）；
5. CI 稳定化（TestPorts 端口分配器、surefire 失败重跑、BuildKit 重试、
   benchmark 组与功能门禁分离并补全 71 类）。

验收：连续两轮 7/7 全绿 + Trivy 0 漏洞 + 全量回归 0 failures。
状态与证据：docs/release/v3.7.1-rc-maintenance-notes.md。

## v4.0 阶段计划

| 里程碑 | 版本 | 范围 | 主要交付物 | ADR | 验收口径 |
| --- | --- | --- | --- | --- | --- |
| M1 | v4.0-M1 | 向量存储接入 | HNSW 持久化闭环（索引落盘/重建/加载）、向量 BlockCache 与 mmap 接入、混合检索（SQL WHERE + 向量 top-K） | ADR-0319 | ✅ 阶段交付中（2026-08-14）：VectorIndexFile/Store/MmapReader/VectorSqlSearch + E2E + 基准报告 |
| M2 | v4.0-M2 | 多模型编码 | 类型化值（SQL/JSON/时序/向量）additive 编码、RESP3 类型接线、TTL/过期/迁移对多模型值兼容 | ADR-0320 | ✅ 完成（2026-08-14）：ValueType 6/7/8 + MultiModelCodec + 命令族 + 自动索引 + WAL/SSTable/迁移/复制闭环 + TTL + RESP3 接线 + 基准报告 |
| M3 | v4.0-M3 | 多集群复制接线 | 联邦一致性验证器 → 真实跨集群复制通道（CDC/日志搬运）、冲突策略（last-write / CRDT 选型）、多活故障切换演练 | ADR-0321 | ✅ 完成（2026-08-14）：REPLICATION RPC + EventCodec + LWW + Sink/Channel + 水位持久化 + Pipeline 串联 + 分区混沌 + E2E/一致性验证 |
| M4 | v4.0-GA | 生产收口 | Operator 完整化、多云部署深化、Jepsen 外部化分区注入、真实 Runner 性能基线（冷/热口径） | ADR-0322 | ✅ 完成（2026-08-14，v4.0.0-rc1）：CapacityModel + Operator 状态机 + 多集群拓扑/计划器 + Jepsen 外部化 + 冷热基线（6.3x）+ 门禁 7/7 |

## 各阶段要点

### M1 — 向量存储接入

- 现状：HNSW 可持久化（Phase 54），缺存储引擎接入与查询接线；
- 交付：向量写入经 WAL/SSTable 持久化、mmap 随机读取、混合检索
  （标量过滤 + 向量 top-K）、索引重建与校验；
- 进度（2026-08-14）：ADR-0319 批准；VectorIndexFile（magic/version/
  CRC + 原子写）、VectorIndexStore（checkpoint/load/rebuild）、
  VectorIndexMmapReader（MappedFile + BlockCache）、VectorSqlSearch
  （SQL 向量索引校验 + 标量过滤）已交付；E2E 与基准报告完成；
- 约束：v1.0–v3.7 冻结协议不变，新能力 additive + ADR。

### M2 — 多模型编码

- 现状：值模型以 byte[] 为主，SQL/向量为独立原型；
- 交付：类型化值编码（additive，向后兼容）、RESP3 类型映射、
  冷热迁移/复制/TTL 对多模型值统一；
- 进度（2026-08-14）：ADR-0320 批准；ValueType 增加 JSON /
  TIME_SERIES / VECTOR（类型字节 6/7/8，1–5 冻结）；MultiModelCodec
  （JSON UTF-8 / 时序 16B 点 / 向量 dim+float[]）+ RESP3 映射
  （bulk / 嵌套数组 / double 数组）已交付；TYPE 命令支持新类型；
  多模型值命令（JSON.SET/GET、TS.ADD/GET/LEN、VECTOR.SET/GET）与
  WAL 恢复 / SSTable 读写 / 复制投递闭环验证完成；编码基准
  JSON 2.76M、时序 320K、向量 646K ops/s；M2 增强：JSON 结构校验
  （JsonValidator）与向量索引存储层同步（VectorIndexSyncStorageEngine，
  put/delete 自动维护）；
- 约束：WAL/SSTable 格式版本冻结，新类型走版本化扩展字段。

### M3 — 多集群复制接线

- 现状：联邦一致性验证器已交付（Phase 55），复制通道未接线；
- 交付：跨集群日志搬运 + 应用端冲突策略 + 一致性验证接入 CI；
- 进度（2026-08-14）：ADR-0321 批准；RpcMessageType 增加
  REPLICATION(34)/REPLICATION_RESPONSE(35)；ReplicationEventCodec
  （CRC32C）、LwwConflictResolver（timestamp + cluster id + seq 幂等）、
  CrossClusterSink（目标端 LWW 应用）、CrossClusterReplicationChannel
  （复用 MultiRaftEndpoint RPC）已交付；E2E 覆盖单写一致 / 双写收敛 /
  重复幂等 / FederationConsistencyVerifier 接线；收尾：CrossClusterWatermark
  （目标端水位原子落盘 + 重启续传）、CrossClusterReplicaSink
  （ReplicationPipeline 串联）、分区/恢复混沌（失败缓存重放幂等）；
  修复端点分发未路由 REPLICATION 到业务 handler 的缺陷；
- 约束：Raft safety / MVCC consistency / 事务状态机不改。

### M4 — 生产收口

- Operator 完整化（备份/恢复/滚动升级/多集群编排）；
- Jepsen 外部化（分区/网络故障注入进真实 Runner）；
- 性能基线：内存、服务端、生产全链路三级口径，冷/热缓存分开报告。
