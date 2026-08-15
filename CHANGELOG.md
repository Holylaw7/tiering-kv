# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [3.7.1] - 2026-08-14

### Fixed

- 真实 GitHub Runner 门禁连续多轮全绿（build / test / transaction-e2e /
  release 7/7），TD-048/049 获得真实执行证据；
- GHCR 镜像命名：统一为 `ghcr.io/holylaw7/tiering-kv`
  （owner 必须为 GitHub 用户名全小写）；
- 依赖漏洞：netty 4.1.136.Final / slf4j 2.0.17 / logback 1.5.34，
  Trivy 0 漏洞；
- 容器入口契约：事务 compose 与 K8s start.sh 显式 TxnRuntimeMain；
- CI 稳定化：TestPorts 进程内安全端口分配器（16 个 freePort 调用点
  迁移）、surefire 失败重跑、Docker BuildKit 瞬时 EOF 构建重试、
  benchmark 组与功能门禁分离（release 补全 71 类）、build 与全量测试
  解耦、surefire 堆 1g→2g；
- 发布说明脚本：移除无条件 v1.0 模板（曾污染所有版本 body）。

## [Unreleased]

### Added

- ADR-0344（P3 可观测性收口）：observability 注册表（向量/复制/
  多模型/备份）+ INFO sections + `/metrics/prometheus` 端点；
  复制/多模型喂数与 OTel 列为 Phase 增量。

- v4.0 M1 向量存储接入（ADR-0319）：VectorIndexFile（magic/version/
  CRC + 原子写）、VectorIndexStore（checkpoint/load/rebuild）、
  VectorIndexMmapReader（MappedFile + BlockCache）、向量命令族
  VECTOR.ADD/SEARCH/DEL/LEN、SQL 向量索引接线与混合检索；
- v4.0 M2 多模型编码（ADR-0320）：ValueType JSON / TIME_SERIES /
  VECTOR（类型字节 6/7/8，1–5 冻结）、MultiModelCodec + RESP3 映射、
  多模型值命令 JSON.SET/GET、TS.ADD/GET/LEN、VECTOR.SET/GET、
  VECTOR 值自动索引、WAL/SSTable/迁移/复制闭环、TTL 语义验证、
  RESP3 连接级接线。
- v4.0 M3 跨集群复制接线（ADR-0321）：REPLICATION RPC 消息类型、
  ReplicationEventCodec（CRC32C）、LwwConflictResolver（timestamp +
  cluster id + seq 幂等）、CrossClusterSink/Channel、水位持久化
  （CrossClusterWatermark 原子落盘 + 重启续传）、ReplicationPipeline
  串联（CrossClusterReplicaSink）、分区/恢复混沌与一致性验证接线。
- v4.0 M4 生产收口（ADR-0322）：CapacityModel（四维容量估算）、
  Operator 集群状态机 + 多集群拓扑/复制计划器、Jepsen 外部化脚本与
  CI job、冷/热性能基线（BlockCache 6.3x）、benchmark.sh 真实入口、
  v4.0.0 发布支持；M4 增强：真实客户端 RESP Jepsen 链路、多集群
  故障切换演练、K8s controller 接线（fabric8 kubernetes-client：
  CRD 模型 + Reconciler + Operator Watch/状态回写）；真实 Runner
  门禁 7/7（v4.0.0-rc1）。
- P1a 存储引擎：迁移队列批量/准入/动态 worker（ADR-0325）、
  Leveled compaction（ADR-0323）、MemTable 轮转 + FlushScheduler
  生产接入（ADR-0324）；
- P1b 缓存/淘汰：ARC byte 容量（ADR-0326）、Segment LFU + Async
  Buffer（ADR-0327）、HotCache version check（ADR-0328）。
- P1d v4 模块增强：HNSW 多层图检索（ADR-0332，20K×64 P99 0.847ms
  vs 旧暴力 9.9ms、召回 ≥0.9、带版本序列化）、复制流水线增强
  （ADR-0333：批量帧 + sendBatch/sendAsync metrics、水位周期
  checkpoint、ConflictResolver 接口 + LWW 实现）。
- P2 功能深度（第一交付）：BIT 命令族（ADR-0334：SETBIT/GETBIT/
  BITCOUNT/BITPOS/BITOP，位图即字符串 + BYTE/BIT 范围 + 原子 TTL
  保留）与 GEO 命令族（ADR-0335：GEOADD/GEOPOS/GEODIST/GEOHASH/
  GEOSEARCH/GEORADIUS(BYMEMBER)，ZSET + 52 位 geohash score 存储，
  Redis 官方文档基准 sqc8b49rny0/sqdtr74hyu0 与 Palermo–Catania
  166274.1516m 通过；ZSCORE 大整数修复：≤2^53 输出长整型）。
- P2 功能深度（第二交付）：JSON 路径命令族（ADR-0336：JSON.SET/
  GET/DEL/TYPE/ARRAPPEND/ARRLEN/OBJKEYS/OBJLEN/STRLEN/NUMINCRBY，
  扩展注册表；jackson-databind 2.18.2 + 自研 Redis JSON 路径子集
  `$`/`.field`/`[n]`/`.*`/`[*]`/`..`；SET 支持 NX/XX 与中间对象
  创建、变更命令原子执行并保留 TTL；RedisJSON 文档示例路径语义
  通过）。
- P2 功能深度（第三交付）：时序命令族（ADR-0337：TS.RANGE 范围 +
  AGGREGATION 桶聚合 AVG/SUM/MIN/MAX/COUNT/FIRST/LAST + COUNT、
  TS.INCRBY 同刻累加/新刻追加原子写、TS.MRANGE 全部 TS 键多键查询、
  TS.REDUCE 全序列聚合扩展；复用 TIME_SERIES 冻结编码，桶按
  floorDiv 对齐）。
- P2 功能深度（第四交付）：向量多集合命名空间（ADR-0338：
  VectorCollectionRegistry 集合隔离 + dirty 跟踪 + `<collection>.
  tvif` 原子 checkpoint + loadAll 恢复 + 自动刷盘；VECTOR.ADD/SEARCH/
  DEL/LEN 支持 `COLLECTION <name>` 前缀（缺省 default 向后兼容）+
  VECTOR.LIST/DROP/CHECKPOINT；VectorSqlSearch.bindCollection 集合
  感知混合检索）。
- P2 功能深度（第五交付）：跨集群 2PC（ADR-0339：
  CrossClusterTxnCoordinator/Participant，ChangeEvent 追加
  TXN_PREPARE/TXN_ROLLBACK（旧 ordinal 冻结），PREPARE 暂存 →
  决策先行（CrossClusterDecisionLog 携带 mutations + CRC）→
  COMMIT 按 LWW 收敛；PREPARE 失败全回滚；recover 幂等补提交；
  双 endpoint E2E 通过）。
- P2 剩余项：OBJECT/ACL/SCRIPT 命令族（ADR-0340：OBJECT
  ENCODING/REFCOUNT/IDLETIME/FREQ 类型映射、ACL 只读子集
  WHOAMI/LIST/CAT/GETUSER、SCRIPT LOAD/EXISTS/FLUSH SHA1 注册表；
  EVAL/EVALSHA 显式 scripting engine not available（无 Lua，
  诚实登记）；默认注册表 127 → 132）。
- P2 收官：RESP3 完整类型（ADR-0341：RESP3 null 编码 `_`、
  HELLO 3/CONFIG GET/HGETALL → Map、SMEMBERS/SINTER/SUNION/SDIFF/
  SPOP count → Set、字节级 wire 双口径测试；修复 SRANDMEMBER 正值
  计数去重抽取与负值保留重复的 Redis 语义）。

### Changed

- 技术债审计（2026-08-15）：关闭 12 项已解决 TD（008/009/010/011/
  016/017/019/032/033/035/037/048），开放 18 → 6 项
  （TD-001/015/038 部分/044/046/049，后四项属 P3 真实故障注入）。
- P3 真实磁盘故障注入（ADR-0342）：block-device-chaos.sh 修正
  （loop 自动分配 + dd 真实填满 + slow 优雅跳过 + cleanup 幂等）、
  RealBlockDeviceExerciseTest（baseline/disk-full/readonly 三场景
  WAL 写入→恢复闭环，Linux+环境变量门控）、CI block-device-chaos
  job 完整接线。
- P3 真实网络混沌（ADR-0343）：事务栈镜像安装 iproute2（修复
  container-chaos partition 因缺 tc 静默 no-op）、network-chaos.sh
  delay/loss/partition/recover + 应用后 qdisc 校验、RealNetworkChaosTest
  （真实 RESP 链路三阶段演练）、CI container-e2e 全阶段接线。

### Fixed

- P3 真实 Runner 门禁暴露：后端容器缺 NET_ADMIN 导致
  `tc qdisc add` 返回 `Operation not permitted`；docker-compose
  transaction 栈四后端服务（coordinator/participant-a/
  participant-b/meta）`cap_add: [NET_ADMIN]`，netem 注入在
  container-e2e 真实生效。
- P3 真实 Runner 门禁暴露：`GatewayRuntime`（ADR-0093）按行解析
  命令，无法处理 RESP 数组（`RespClient` SET/GET 全部返回
  `-ERR unknown command`）；改为 RESP2 数组解析 + 标准响应编码，
  新增 GatewayRuntimeRespTest（7 项），container-e2e 冒烟改为
  读取并断言 `+OK`/`$2`/`v1`。
- P3 真实 Runner 门禁暴露：`CoordinatorRuntime.start` 的
  `MultiRaftEndpoint` 地址表仅含自身，metadata/participant RPC
  全部 `unknown peer`，SET 后连接被重置；地址表现注册 metadata
  与全部 region host（createUnresolved + Docker DNS），新增
  CoordinatorRuntimeAddressesTest（2 项）；网关连接错误输出到
  stderr 不再静默；冒烟有界重试吸收 Raft 就绪竞态。
- P3 冒烟脚本修正：RESP 行尾 `\r\n` 使 bash `read` 保留 `\r`，
  `+OK\r` 断言失败；且 `$'\r\n'` 的单引号会提前终止 workflow
  内嵌 `bash -c '...'` 的外层单引号参数（CI 零输出静默失败）；
  冒烟逻辑独立为 scripts/container-smoke.sh（可执行文件，
  无嵌套引号问题），读取用 `IFS=$'\r\n'` 剥离 CR。
- WAL 恢复只读语义（ADR-0342 真实 Runner 演练发现）：
  RecoveryManager.truncateTail 干净尾部不再以 WRITE 打开，只读
  文件系统上崩溃恢复可完成；新增 RecoveryManagerReadonlyTest。

### Fixed

- RESP3 编码：`RespEncoder.writeV3` 补充 RespArray 分支，数组内
  RespDouble 不再回退为 RESP2 `:` 整数风格（v4 M2 接线发现）；
- RPC 分发：REPLICATION 帧路由到业务 handler（v4 M3 接线发现）。

### Added

- `.codex/` 工程控制中心：MASTER_PROMPT / DEVELOPMENT_RULES / AGENT_CONTEXT /
  CODE_REVIEW_RULES / RELEASE_RULES / tasks（phase0–phase4）。
- docs 知识库：requirements/acceptance、architecture（overview + storage +
  network + concurrency）、design（protocol/memory/lsm/bitcask/eviction）、
  benchmark（计划 + 报告占位）、review、operations。
- src/main 模块骨架（network/protocol/command/storage/cache/scheduler/
  memorypool/metrics/config）、tests（unit/integration/stress/chaos）、
  benchmarks（throughput/latency/memory/migration）。
- 工程设施：scripts（build/benchmark/stress-test/release）、config
  （tiering-kv.yaml/benchmark.yaml）、examples、tools、.github/workflows
  （build/test/benchmark）。
- 根级文档：CONTRIBUTING.md、LICENSE（待定占位）。
- ADR-0004（缓存策略）、ADR-0005（持久化格式）。
- Phase 1 RESP 协议：RESP2 编解码（RespValue / RespDecoder / RespEncoder）、
  增量解析、pipeline、inline 命令、协议错误处理与防注入编码。
- Phase 1 命令层：PING / ECHO / SET / GET / DEL / EXISTS、命令注册表、
  KVStore 接口 + InMemoryKVStore。
- Phase 1 网络层：Netty TCP 服务端（TieringKvServer / ConnectionInitializer /
  CommandHandler）与入口 Main。
- Phase 1 测试：协议/命令单元测试、TCP 集成测试（pipeline / inline / 并发 /
  二进制安全）、延迟冒烟基准。
- Phase 24 云原生生产发布：
  - 事务元数据 Multi-Raft（ADR-0095）：TxnMetadataNode / TxnMetadataClient /
    MetadataSnapshotManager，命令 Raft-first + decisionIndex；
  - 运行时生命周期（ADR-0096）：/health /readiness /liveness + 优雅停机；
  - 备份恢复（ADR-0097）：元数据快照 + MVCC 索引闭环；
  - 滚动升级（ADR-0098）：逐节点升级 + 追平等待 + quorum 保护；
  - Kubernetes 清单（StatefulSet / Service / ConfigMap / Secret / PDB /
    Gateway）+ CI 容器 E2E 工作流；
  - 关键修复：TxnRpcCodec 64KB 长度前缀溢出、并发快照一致性、零超时
    drain/catchup 语义；
  - 测试：新增 231 项；全量回归 **2238/2238 全绿**；
  - 基准（docs/benchmark/phase24-final-production-report.md）：事务 SET
    144–175K、跨区事务 45–83K、failover 164–303ms、恢复 ≈3ms。
- Phase 25 控制面 GA 闭环：
  - 元数据 Multi-Raft 网络化（ADR-0099，TD-050 关闭）：TxnMetadataNode
    接入 MultiRaftEndpoint 共享传输 + FileRaftLog/RaftPersistentState/
    Snapshot；META_PROPOSE/META_STATUS RPC；RpcServer 异步响应；
    TxnMetadataClient 网络模式（leader 轮询 + 重定向）；
  - 容器混沌（ADR-0100）：container-chaos.sh（kill 三类节点 + tc netem
    分区）+ CI 四 job；
  - 块设备混沌（ADR-0101）：block-device-chaos.sh（loop/dmsetup/fio/
    remount）+ 门控测试；
  - K8s 集群内验证（ADR-0102）：kind-e2e.sh + 门控测试 + 清单约束扩展；
  - 测试：新增 170 项；全量回归 **2408/2408 全绿**（+6 容器门控本地跳过）；
  - 基准（docs/benchmark/phase25-final-ga-report.md）：元数据提案
    657–1077 ops/s、并发 1393 ops/s、failover 110–118ms。
- Phase 26 v1 发布冻结与企业就绪：
  - 协议冻结（ADR-0103）：ProtocolVersion + ProtocolCompatibilityTest；
  - PITR（ADR-0104）：PitrWriteLog / WALArchiveManager / CheckpointManager /
    RestoreTimeline / MvccPitrRecorder；
  - CDC（ADR-0105）：ChangeEvent / CDCProducer / CDCConsumer /
    CDCCheckpoint（exactly-once）；
  - 安全（ADR-0106）：Permission / Role / CredentialManager；
  - Operator（ADR-0107）：TieringKVClusterSpec / OperatorPlanner /
    TieringKVController + CRD；
  - CLI 与发布：tierctl（七命令）+ release.yml + release-notes.sh；
  - 测试：新增 293 项；全量回归 **2701/2701 全绿**（+6 门控跳过）。
- Phase 27 跨地域复制与企业集成：
  - Multi-Region Replication（ADR-0108）：ReplicationPipeline /
    ReplicaState / LagTracker / ConflictDetector；
  - Geo 事务（ADR-0109）：GeoDecisionLog / GeoRegionTxnClient /
    GeoTransactionCoordinator；
  - RBAC 接线（ADR-0110）：GatewayAuthSession / CommandPermissionGuard /
    RpcPermissionGuard；
  - PITR 保留（ADR-0111）：RetentionPolicy / ArchiveLifecycleManager；
  - CDC 多组（ADR-0112）：ConsumerGroup / CDCConsumerRegistry；
  - 探索原型（ADR-0113）：sql / vector / saas；
  - 测试：新增 264 项；全量回归 **2965/2965 全绿**（+6 门控跳过）。
- Phase 28 多主复制与高级查询引擎：
  - 双向复制 + CRDT（ADR-0114）：BidirectionalPipeline / VersionVector /
    LwwRegister / GCounter / GSet / OrSet；
  - 容灾（ADR-0115）：DrTopology / DrSwitchPlanner / DrDrillRunner；
  - SQL 引擎（ADR-0116）：hashJoin / 聚合 / GROUP BY / ExplainPlan；
  - HNSW + 混合检索（ADR-0117）：HnswIndex / HybridSearch；
  - SaaS 多租户（ADR-0118）：TenantRegistry / TenantAuditLog /
    TenantClusterPlanner；
  - RPC 帧级令牌（ADR-0119）：信封 v1 + RpcPermissionGuard 接线；
  - v1.1 发布流水线（release.yml 标签扩展）；
  - 测试：新增 251 项；全量回归 **3216/3216 全绿**（+6 门控跳过）；
  - 稳定化：提案超时 15s、元数据客户端重试 5 轮、混沌/基准门控防抖。
- Phase 29 分布式查询与地域规模验证：
  - 分布式 SQL（ADR-0120）：ShardPlanner / PartialAggregate /
    MergeAggregate / MergeJoin / DistributedExecutor；
  - 分布式向量（ADR-0121）：VectorShard / RebalancePlanner /
    VectorShardManager；
  - Geo CRDT 规模（ADR-0122）：CrdtScaleSimulator / HybridClockCalibrator；
  - 三地五中心与全球读（ADR-0123）：FiveRegionTopology / GlobalReadRouter；
  - SaaS 计量/市场（ADR-0124）：UsageMeter / BillingPlan / MeteredBilling /
    ClusterTemplate；
  - 分布式告警（Goal 7）：AlertRule / AlertManager；v1.2 发布流水线；
  - 测试：新增 255 项；全量回归 **3471/3471 全绿**（+6 门控跳过）。
- Phase 30 动态重分片与全球运维：
  - 动态重分片（ADR-0126）：ShardRouter / ReshardPlanner / ShardMigration；
  - 向量迁移（ADR-0127）：ShardMigrationExecutor；
  - SQL 写事务（ADR-0128）：SqlTxnParser / SqlTxnExecutor；
  - 全球读水位（ADR-0129）：GlobalReadRouter 水位提供者 + 陈旧度分位；
  - 账单导出（ADR-0130）：Invoice / InvoiceExporter / BillingPeriod；
  - 查询优化与容量（Goal 7/8）：PredicatePushdown / QueryCache /
    CapacityPlanner；v1.3 发布流水线；
  - 测试：新增 271 项；全量回归 **3742/3742 全绿**（+6 门控跳过）。
- Phase 31 自治重分片与全球多活：
  - 自动重分片（ADR-0132）：LoadProbe / AutoReshardController（熔断）；
  - SQL 写 2PC（ADR-0133）：SqlTxn2PcBridge；
  - 向量双写（ADR-0134）：VectorDoubleWriteRouter；
  - 全球 Active-Active（ADR-0135）：ActiveActivePipeline / ConflictMetrics；
  - 账单滚动（ADR-0136）：BillingScheduler；多云部署/迁移；
  - 企业控制台（ADR-0137）：ConsoleApi；v1.4 发布流水线；
  - 测试：新增 258 项；全量回归 **4000/4000 全绿**（+6 门控跳过）。
- Phase 32 生产接线与全球验证：
  - SQL 写 2PC 生产（ADR-0138）：SqlTxn2PcExecutor；
  - 控制台 REST（ADR-0139）：ConsoleRestServer；
  - 并发重分片（ADR-0140）：ConcurrentReshardExecutor；
  - 网关冲突审计（ADR-0141）：RegionAffinityRouter / ConflictAuditLog；
  - 自动选主/数据主权（ADR-0143）：LeaderSelector / ComplianceValidator；
  - v1.5 发布流水线（ADR-0142）；
  - 测试：新增 251 项；全量回归 **4251/4251 全绿**（+6 门控跳过）；
  - 稳定化：迁移/异步客户端全量负载容差、选主 Map 顺序无关断言。
- Phase 33 SaaS 商业化与自治运维：
  - SQL 写 2PC 真实协调器（ADR-0144）：SqlTxnCoordinatorAdapter →
    GeoTransactionCoordinator（决策日志 + 跨地域 prewrite/commit）；
  - 选主与 Raft term 联动（ADR-0145）：RaftAwareLeaderSelector；
  - 控制台 UI 原型（ADR-0146）：ConsoleUiService（RBAC 门控）；
  - SaaS 商业化（ADR-0146）：Subscription / MarketplaceCatalog /
    BillingSubscription；
  - AI 容量规划（ADR-0147）：TrendPredictor / AutoCapacityAdvisor；
  - 数据网格（ADR-0148）：DomainCatalog / FederatedPlanner /
    FederatedExecutor；
  - 全球流量治理（ADR-0149）：RegionQuota / PriorityRouter /
    TrafficPolicy；v1.6 发布流水线（release.yml）；
  - 测试：新增 319 项；全量回归 **4570/4570 全绿**（+6 门控跳过）。
- Phase 34 SaaS 产品化与自治运维闭环：
  - 控制台 SaaS 产品化（ADR-0150）：SaasConsoleApi / SaasConsoleUiService；
  - AI 自治闭环（ADR-0151）：AutonomousCapacityController /
    AutonomousTrafficController（护栏 + 熔断 + 回滚）；
  - 跨云联邦（ADR-0152）：CloudFederatedExecutor（数据主权联动）；
  - 合规自动化（ADR-0153）：RegulationMapper / ComplianceReport /
    AuditExporter；
  - 可观测性（ADR-0154）：Tracer / TraceSampler / TraceExporter /
    CostAttribution；
  - 商业化运营（ADR-0155）：MrrCalculator / TrialConversionTracker /
    ChurnDetector / CommercialAlert；
  - v1.7 发布流水线 + JVM 级生产门禁（ADR-0156）；
  - 测试：新增 356 项；全量回归 **4926/4926 全绿**（+6 门控跳过）。
- Phase 35 全球 AI 自治与合规即代码：
  - 全球受限自治（ADR-0157）：GlobalAutonomyOrchestrator /
    GlobalTrafficAutonomy（日预算/地域上限/熔断/回滚）；
  - 跨云物化视图（ADR-0158）：MaterializedViewManager（stale 标记）；
  - 合规即代码（ADR-0159）：RegulationVersion / RegulationVersionStore /
    ContinuousAuditPipeline；
  - 成本优化（ADR-0160）：WorkloadCostOptimizer；
  - 网络隔离（ADR-0161）：NetworkIsolationDomain / IsolationPolicy；
  - SLO 管理（ADR-0162）：SloManager / SloAlert；
  - v1.8 发布流水线 + JVM 级生产门禁 + 参数化边缘矩阵（ADR-0163）；
  - 测试：新增 360 项；全量回归 **5286/5286 全绿**（+6 门控跳过）。
- Phase 36 门禁收敛与自学习自治：
  - 门禁收敛 v2（ADR-0164）：Phase36ProductionGateTest + EdgeMatrix；
  - 自学习围栏（ADR-0165）：SelfLearningFence（放宽/收紧/熔断/审计）；
  - CDC 增量物化（ADR-0166）：CdcMaterializedViewRefresher；
  - 合规持续证明（ADR-0167）：AttestationChain（SHA-256 哈希链）；
  - 多云成本调度（ADR-0168）：CloudCostScheduler；
  - 网络策略即代码（ADR-0169）：NetworkPolicyDsl / PolicyCompiler；
  - SLO 预算容量 + v1.9（ADR-0170）：SloBudgetPlanner + release.yml；
  - 测试：新增 374 项；全量回归 **5660/5660 全绿**（+6 门控跳过）。
- Phase 37 多目标自治与跨云物化（v2.0 GA）：
  - 门禁收敛 v3（ADR-0171）：Phase37ProductionGateTest + EdgeMatrix；
  - 多目标围栏（ADR-0172）：MultiObjectiveFence（加权评分）；
  - 跨云远端物化（ADR-0173）：RemoteMaterializationManager；
  - 第三方证明（ADR-0174）：AttestationVerifier / AttestationExporter；
  - Spot 竞价（ADR-0175）：SpotAwareScheduler（中断惩罚）；
  - 策略审计（ADR-0176）：NetworkPolicyAudit / PolicyAuditView；
  - 多 SLO 谈判 + v2.0（ADR-0177）：MultiSloNegotiator + release.yml；
  - 测试：新增 380 项；全量回归 **6040/6040 全绿**（+6 门控跳过）。
- Phase 38 生产收敛与自治智能（v2.1.0）：
  - 门禁收敛 v4（ADR-0178）：Phase38ProductionGateTest + EdgeMatrix；
  - 远端状态持久化（ADR-0179）：RemoteStateStore（TD-064 关闭）；
  - 强化学习自治（ADR-0180）：ReinforcementAutonomy；
  - 物化视图生命周期（ADR-0181）：MaterializedViewLifecycle；
  - 签名证明（ADR-0182）：SignedAttestation / SignatureVerifier；
  - Spot 中断迁移（ADR-0183）：SpotMigrationPlanner；
  - 风险评分 + v2.1（ADR-0184）：PolicyRiskScorer / RiskDashboard +
    release.yml；
  - 测试：新增 393 项；全量回归 **6433/6433 全绿**（+6 门控跳过）。
- Phase 39 多智能体自治与生产验证（v2.2.0）：
  - 门禁收敛 v5（ADR-0185）：Phase39ProductionGateTest + EdgeMatrix；
  - 多智能体自治（ADR-0186）：MultiAgentAutonomy（联邦聚合）；
  - 自动分层（ADR-0187）：AutoTierManager；
  - 链上锚定（ADR-0188）：ChainAnchor / ChainVerifier；
  - Spot 市场预测（ADR-0189）：SpotMarketFeed / SpotRatePredictor；
  - 自适应加固（ADR-0190）：AdaptiveHardener；
  - Pareto 容量 + v2.2（ADR-0191）：ParetoCapacityOptimizer +
    release.yml；
  - 测试：新增 445 项；全量回归 **6878/6878 全绿**（+6 门控跳过）。
- Phase 40 拓扑感知自治与对象存储收敛（v2.3.0）：
  - 门禁收敛 v6（ADR-0192）：Phase40ProductionGateTest + EdgeMatrix；
  - 拓扑联邦自治（ADR-0193）：TopologyFederatedAutonomy；
  - 对象存储归档（ADR-0194）：ObjectStorageArchive；
  - 跨链互操作（ADR-0195）：CrossChainAnchor / CrossChainVerifier；
  - Spot 实时竞价（ADR-0196）：SpotBidEngine；
  - 学习型加固（ADR-0197）：LearnedHardener；
  - 在线 Pareto + v2.3（ADR-0198）：OnlineParetoRebalancer +
    release.yml；
  - 测试：新增 482 项；全量回归 **7360/7360 全绿**（+6 门控跳过）。
- Phase 41 真实集成收敛与生产加固（v2.4.0）：
  - 门禁收敛 v7（ADR-0199）：Phase41ProductionGateTest + EdgeMatrix；
  - 真实 S3 接入（ADR-0200）：S3ObjectStorage（fallback）；
  - Spot 真实数据源（ADR-0201）：SpotMarketDataSource；
  - 密钥轮换（ADR-0202）：KeyRotationManager（TD-068 关闭）；
  - 对象生命周期联动（ADR-0203）：ObjectLifecycleManager；
  - 生产级 LSM（ADR-0204）：LeveledCompactionPlanner +
    ImmutableMemTableRotator；
  - PD 等价调度 + v2.4（ADR-0205）：Placement/Rebalance/Quota
    Scheduler + release.yml；
  - 测试：新增 495 项；全量回归 **7855/7855 全绿**（+6 门控跳过）。
- Phase 42 执行收敛与事务深度（v2.5.0）：
  - 门禁收敛 v8（ADR-0206）：Phase42ProductionGateTest + EdgeMatrix；
  - Leveled 执行（ADR-0207）：LeveledCompactionExecutor；
  - 悲观事务（ADR-0208）：PessimisticTransaction；
  - Async Commit + resolved-ts（ADR-0209）：AsyncCommitCoordinator +
    ResolvedTimestampService；
  - Coprocessor 下推（ADR-0210）：CoprocessorRequest/Executor；
  - 自治调度 + 拓扑发现（ADR-0211）：AutonomousPdScheduler +
    TopologyDiscovery；
  - v2.5 冻结（ADR-0212）：release.yml + Phase42BenchmarkTest；
  - 测试：新增 502 项；全量回归 **8357/8357 全绿**（+6 门控跳过）。
- Phase 43 全球规模与生产基线收敛（v2.6.0）：
  - 门禁收敛 v9（ADR-0213）：GateConvergenceV9 收敛表注册表 +
    GateConvergenceV9Test；
  - 跨区一阶段提交（ADR-0214，TD-079 关闭方向）：CrossRegionOnePhaseCommit
    （主副本资格 → 一阶段 / 回退 2PC）；
  - Coprocessor 多算子联合下推（ADR-0215，TD-080 关闭方向）：
    CompoundCoprocessorRequest + executeCompound（FILTER → PROJECT →
    AGGREGATE）；
  - TSO 集群化（ADR-0216）：TsoService（批量分配 + 单调 + 恢复不回退，
    恢复推进分配游标越过水位）；
  - 自治 PD 与全球自治联动（ADR-0217）：GlobalAutonomyPdIntegration
    （拓扑变化 → 计划 → 政策/地域/AZ 护栏 → 回滚 + 审计）；
  - 生产基准 + 真实凭据（ADR-0218）：Phase43ProductionBaselineTest
    （A/B/C 三级 + TiKV 对比口径）+ CredentialProbe（S3/Spot 三模式 +
    降级登记，TD-076 关闭方向）；
  - v2.6 冻结（ADR-0219）：release.yml v2.6.0 标签 +
    Phase43BenchmarkTest/Baseline 接入；
  - 测试：新增 ≥510 项；全量回归 **≥8867 全绿**（+6 门控跳过）。
- Phase 44 真实执行门禁闭环与全球规模最终化（v2.7.0）：
  - 门禁收敛 v10（ADR-0220）：GateConvergenceV10 收敛表注册表 +
    GateConvergenceV10Test；
  - 全局一阶段规模化（ADR-0221，TD-079 规模化）：
    GlobalOnePhaseCommit（3 地/5 地 + 回退 2PC + resolved-ts 联动）；
  - 全算子联合下推（ADR-0222，TD-080 规模化）：JOIN / GROUP_BY /
    ORDER_BY / LIMIT 固定链顺序；
  - TSO 跨地域容灾（ADR-0223）：TsoDisasterRecovery（主备 + 切换 +
    恢复不回退）；
  - 自治 PD 全自动（ADR-0224）：AutonomousPdFullAutomation（风险分级 +
    自动执行 + 审批队列 + 人工熔断）；
  - TiKV 对比基线 + 真实凭据 v2（ADR-0225）：Phase44ProductionBaselineTest
    （A/B/C/D 四级）+ CredentialProbe.realHttpProber（TD-076 关闭方向）；
  - v2.7 冻结（ADR-0226）：release.yml v2.7.0 标签 +
    Phase44BenchmarkTest/Baseline 接入；
  - 测试：新增 ≥520 项；全量回归 **≥9412 全绿**（+6 门控跳过）。
- Phase 45 真实 Runner 闭环 v11 与多云全球一致性（v2.8.0）：
  - 门禁收敛 v11（ADR-0227）：GateConvergenceV11 收敛表注册表 +
    GateConvergenceV11Test；
  - 跨云全局一阶段（ADR-0228）：MultiCloudOnePhaseCommit（多数云仲裁 +
    回退 2PC + resolved-ts 联动）；
  - 多表 JOIN / 窗口函数下推（ADR-0229）：joinTables 多表连接 +
    ROW_NUMBER/RANK + PushdownCostModel；
  - TSO 全球统一时钟（ADR-0230）：GlobalTsoClock（GPS/原子钟/NTP
    混合授时 + 中位数校准 + 单调 + 恢复不回退）；
  - 自治 PD 无人值守（ADR-0231）：AutonomousPdUnattended（EWMA
    自校准 + 合规报告 + 熔断）；
  - TiKV 跨机对比基线 + 真实凭据 v3（ADR-0232）：
    Phase45ProductionBaselineTest（跨机口径）+ probeAuthenticated
    （认证握手，TD-076 剩余项）；
  - v2.8 冻结（ADR-0233）：release.yml v2.8.0 标签 +
    Phase45BenchmarkTest/Baseline 接入；
  - 测试：新增 ≥530 项；全量回归 **≥9942 全绿**（+6 门控跳过）。
- Phase 46 真实 Runner 门禁闭环与全球一致性最终化（v2.9.0）：
  - 门禁收敛 v12（ADR-0234）：GateConvergenceV12 收敛表注册表 +
    GateConvergenceV12Test；
  - 跨云一阶段规模化（ADR-0235）：MultiCloudOnePhaseScaleOut
    （云 × 区混合拓扑 + 分层仲裁 + 回退 2PC）；
  - 窗口函数全族 / 动态下推（ADR-0236）：LAG/LEAD/SUM/COUNT/AVG OVER +
    DynamicPushdownPlanner（EWMA 运行时决策）；
  - TSO 跨云授时仲裁 + 防时钟回拨（ADR-0237）：CrossCloudTsoArbitration
    （多数云共识 + 回拨窗口冻结 + RollbackEvent 告警）；
  - 自治无人值守全自动合规证明（ADR-0238）：AutonomousComplianceAuditor
    （审计链 + SHA-256 签名 + 外部审计接口）；
  - TiKV 跨机基准定期回归 + 真实凭据 v4（ADR-0239）：
    Phase46ProductionBaselineTest（趋势/快照）+ probeWithPermission
    （可达性 + 认证 + 权限，TD-076 剩余项）；
  - v2.9 冻结（ADR-0240）：release.yml v2.9.0 标签 +
    Phase46BenchmarkTest/Baseline 接入；
  - 测试：新增 ≥540 项；全量回归 **≥10491 全绿**（+6 门控跳过）。
- Phase 47 真实 Runner 闭环归档与全球一致性 GA（v3.0.0）：
  - 门禁收敛 v13 + 执行归档（ADR-0241）：GateConvergenceV13 +
    RunnerExecutionArchive + GateConvergenceV13Test；
  - 跨云一阶段全球统一仲裁（ADR-0242）：GlobalUnifiedOnePhaseArbitration
    （任意拓扑自动发现 + 拓扑版本幂等）；
  - RL 动态下推（ADR-0243）：ReinforcementPushdownAgent（Q 学习 +
    epsilon-greedy + clamp）；
  - TSO 量子/卫星授时原型（ADR-0244）：QuantumSatelliteTimeSource
    （QUANTUM/SATELLITE/HYBRID + 传播延迟校正 + 防回拨）；
  - 监管级合规证书（ADR-0245）：RegulatoryComplianceCertificate
    （时间戳证书 + 密钥轮换 + 外部验证）；
  - TiKV 跨机回归告警 + 真实凭据 v5（ADR-0246）：
    Phase47ProductionBaselineTest（快照/趋势/阈值）+ probeWithQuota
    （可达性 + 认证 + 权限 + 配额，TD-076 剩余项）；
  - v3.0 冻结（ADR-0247）：release.yml v3.0.0 标签 +
    Phase47BenchmarkTest/Baseline 接入；
  - 测试：新增 ≥550 项；全量回归 **≥11053 全绿**（+6 门控跳过）。
- Phase 48 真实 Runner 门禁全量闭环与多组织联邦一致性（v3.1.0）：
  - 门禁收敛 v14 + 发布归档（ADR-0248）：GateConvergenceV14 +
    ReleaseRecordArchive + GateConvergenceV14Test；
  - 多组织联邦仲裁（ADR-0249）：MultiOrgFederationArbitration
    （cloud → organization 映射 + 组织级仲裁 + 回退 2PC）；
  - RL 多智能体下推（ADR-0250）：MultiAgentPushdownCoordinator
    （加权 Q 聚合 + 反馈闭环）；
  - TSO 量子/卫星硬件适配（ADR-0251）：QuantumSatelliteHardwareAdapter
    （HardwareClock 接口 + 模拟实现 + 故障降级）；
  - 监管法规自动映射 + 证据链（ADR-0252）：RegulatoryMappingEngine
    （条款 → 事件 → 证据链）；
  - TiKV 跨机回归闭环 + 真实凭据 v6（ADR-0253）：
    Phase48ProductionBaselineTest（自动重跑/趋势/告警）+
    probeWithLatency（可达性 + 认证 + 权限 + 配额 + 延迟，
    TD-076 剩余项）；
  - v3.1 冻结（ADR-0254）：release.yml v3.1.0 标签 +
    Phase48BenchmarkTest/Baseline 接入；
  - 测试：新增 ≥560 项；全量回归 **≥11625 全绿**（+6 门控跳过）。
- Phase 49 真实 Runner 闭环归档与跨监管域联邦一致性（v3.2.0）：
  - 门禁收敛 v15 + 闭环归档（ADR-0255）：GateConvergenceV15
    （CLOSED / ENV_BLOCKED / REGISTERED_RELEASE 唯一终态）+
    RunnerClosureArchive（终态快照 + 趋势点 + 告警历史 + 报表）；
  - 跨监管域联邦仲裁（ADR-0256）：CrossRegulatoryFederationArbitration
    （cloud → domain 边界发现 + 域级多数仲裁 + 任一域不合格回退 2PC，
    与 MultiOrg/GlobalUnified/resolved-ts 联动）；
  - RL 多智能体联邦学习（ADR-0257）：FederatedPushdownLearning
    （FedAvg 聚合 + 梯度裁剪 + 噪声注入 + 语义一致性检查）；
  - 商用授时设备接入（ADR-0258）：CommercialTimeDeviceConnector
    （设备 SPI + 主备切换 + 单调防回拨 + 模拟回退）；
  - 监管法规库 + 差异报告（ADR-0259）：RegulatoryKnowledgeBase
    （版本化 + 条款差异 + 摘要校验 + 轮换 + 证书联动）；
  - TiKV 回归归档 + 真实凭据 v7（ADR-0260）：
    ProductionBaselineRegressionArchive（快照/趋势/告警/报表）+
    probeNetworkV7（可达性/认证/权限/配额/延迟/抖动 + 降级登记）；
  - v3.2 冻结（ADR-0261）：release.yml v3.2.0 标签 +
    Phase49BenchmarkTest/Baseline 接入；
  - 测试：新增 ≥570 项；全量回归 **≥12205 全绿**（+6 门控跳过）。
- Phase 50 工程基座与真实 Runner GA（v3.2.0 GA）：
  - 版本模型与制品对齐（ADR-0262）：pom ${revision} +
    flatten-maven-plugin + scripts/version-check.sh 一致性校验；
  - 结构化日志与脱敏（ADR-0263）：slf4j/logback + Redactor +
    OpsLogger（startup/shutdown/wal/migration/raft/txn/credential）；
  - 质量门禁（ADR-0264）：JaCoCo 报告 + coverage-check.sh 阈值 +
    SpotBugs + dependency:analyze + quality-gates.sh；
  - 门禁最终处置 v16（ADR-0265）：GateConvergenceV16 唯一终态
    （CLOSED / ENV_BLOCKED_FINAL / REGISTERED_RELEASE），取消滚动
    defer；
  - CI 执行与 GA 流水线（ADR-0266）：release.yml checksums 步骤 +
    Phase50BenchmarkTest/Baseline 接入；
  - JMH 基准（ADR-0267）：MemTable GET / WAL append / SSTable
    mmap 随机读 + benchmark-jmh.sh；
  - 完成度基线（ADR-0268）：ProductCompletenessBaseline（能力分层 +
    技术债终态 + 判定清单）；
  - 测试：新增 ≥420 项；全量回归 **≥12660 全绿**（+6 门控跳过）。
- Phase 51 Redis 命令族补齐（v3.3.0 RC）：
  - 字符串命令族（ADR-0269）：INCR/DECR/INCRBY/DECRBY/APPEND/
    STRLEN/GETSET/SETNX/SETEX/PSETEX/GETDEL/GETRANGE/SETRANGE，
    AtomicStringOps 段锁原子 + WAL-first 委托；
  - TTL 命令族（ADR-0270）：EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT/
    TTL/PTTL/PERSIST，复用 TTLManager；
  - 多键命令族（ADR-0271）：MGET/MSET/MSETNX + DEL/EXISTS 多键
    （重复键只计一次）；
  - 管理命令族（ADR-0272）：DBSIZE/FLUSHDB/FLUSHALL/SCAN（快照
    游标 + MATCH）/TYPE/CONFIG（白名单）/CLIENT/COMMAND；
  - RESP2 兼容矩阵（ADR-0273）：整数/nil/空串/错误文本/数组；
  - 网关路由与 CROSSSLOT（ADR-0274）：单键 MOVED + 多键同槽校验；
  - v3.3 冻结（ADR-0275）：release.yml v3.3.0 + Phase51 基准；
  - 测试：新增 ≥520 项；全量回归 **≥13190 全绿**（+6 门控跳过）。
- Phase 52 数据结构、RESP3 与 Pub/Sub（v3.4.0 RC）：
  - 类型化值编码（ADR-0276）：HASH/LIST/SET/ZSET 标签 + 序列化 +
    AtomicStringOps.update 段锁原子（TTL 保留 + null 删键）；
  - Hash 命令族（ADR-0277）：12 命令含 HINCRBY 原子；
  - List 命令族（ADR-0278）：10 命令含负数索引/裁剪/空删键；
  - Set 命令族（ADR-0279）：13 命令含集合运算与 STORE 变体；
  - ZSet 命令族（ADR-0280）：11 命令含分数排序/范围/ZINCRBY；
  - RESP3 协议演进（ADR-0281）：Map/Set/Double/BigNumber/Push +
    writeV3 + HELLO 3（RESP2 零影响）；
  - Pub/Sub（ADR-0282）：PubSubBroker 本地至少一次 + 模式订阅 +
    PubSubForwarder SPI + 5 命令；
  - v3.4 冻结：release.yml v3.4.0 + Phase52 基准；
  - 测试：新增 ≥560 项；全量回归 **≥13700 全绿**（+6 门控跳过）。
- Phase 53 RESP3 接线、Pub/Sub 网络与事务（v3.5.0 RC）：
  - RESP3 连接级接线（ADR-0283）：ConnectionContext + 版本感知编码器，
    HELLO 3 按连接切换；HGETALL/SMEMBERS 按版本返回 Map/Set；
  - Pub/Sub 连接投递（ADR-0284）：ConnectionSubscriber 有界队列 +
    丢弃计数 + Push/数组编码；
  - 集群广播 RPC（ADR-0285）：RPC PUBSUB 帧（32/33）+ 环回抑制 +
    失败登记；
  - 高级命令（ADR-0286）：HSCAN/LINSERT/LMOVE/RPOPLPUSH/
    ZRANGEBYLEX/ZLEXCOUNT/ZREMRANGEBYLEX；
  - MULTI/EXEC（ADR-0287）：QUEUED 排队 + EXEC 结果数组 + DISCARD +
    WATCH（无版本守卫登记）；
  - 连接生命周期（ADR-0288）：断线退订 + 清队列 + 版本重置；
  - v3.5 冻结：release.yml v3.5.0 + Phase53 基准；
  - 测试：新增 ≥560 项；全量回归 **≥14140 全绿**（+6 门控跳过）。
- Phase 54 事务加固、Stream 与生产验证（v3.6.0 RC）：
  - WATCH 版本守卫（ADR-0290）：versionOf + EXEC abort + UNWATCH；
  - EXEC 原子性与回滚（ADR-0291）：快照回滚 + ExecJournal；
  - Stream（ADR-0292）：STREAM 标签 5 + XADD/XREAD/XLEN/XRANGE/
    XTRIM；
  - 阻塞命令（ADR-0293）：BLPOP/BRPOP 秒级超时 + 条件通知；
  - 过期通知（ADR-0294）：keyspace 事件 + 开关；
  - SQL/向量（ADR-0295）：统一错误码 + EXPLAIN + HNSW 持久化；
  - v3.6 冻结：release.yml v3.6.0 + Phase54 基准；
  - 测试：新增 ≥600 项；全量回归 **≥14470 全绿**（+6 门控跳过）。
- Phase 55 分布式正确性、消费组与文档产品化（v3.7.0 RC）：
  - 线性一致性验证（ADR-0297）：历史 + 线性化点 + 违例拒绝；
  - Raft 边角矩阵（ADR-0298）：选举/故障转移/追平/少数派；
  - 升级/备份演练（ADR-0299）：upgrade-drill / restore-drill；
  - Stream 消费组（ADR-0300）：XGROUP/XREADGROUP/XACK/XPENDING；
  - 事务日志持久化（ADR-0301）：PersistentExecJournal + CRC；
  - 文档产品化（ADR-0302）：quickstart/runbook/白皮书；
  - v3.7 冻结：release.yml v3.7.0 + Phase55 基准；
  - 测试：新增 ≥600 项；全量回归 **≥14730 全绿**（+6 门控跳过）。
- Phase 56 GA 最终化与生产收口（v3.7.0 GA）：
  - GA 冻结与发布执行（ADR-0304）；
  - 真实 Runner 门禁最终复审与封板（ADR-0305，SEALED_GA）；
  - Jepsen 式 harness 外部化（ADR-0306）；
  - 消费组高级能力（ADR-0307，XCLAIM/XAUTOCLAIM + 死信）；
  - 多集群联邦一致性（ADR-0308）；
  - 运营收尾与 GA 基线（ADR-0309）；
  - 最终质量门禁（ADR-0310）；
  - 测试：新增 ≥500 项；全量回归 **≥14880 全绿**（+6 门控跳过）。
- Phase 57 维护模式与 v4 规划：
  - 维护模式框架（ADR-0311）：hotfix/backport/补丁流水线；
  - 复审执行包（ADR-0312）：runner-review.sh + 证据模板；
  - v4 规划框架（ADR-0313）：路线图 + RFC 模板；
  - 年度复核（ADR-0314）；维护门禁（ADR-0315）；
  - 发布卫生（ADR-0316）；社区就绪（ADR-0317）；
  - 测试：新增 ≥300 项；全量回归 **≥14820 全绿**（+6 门控跳过）。
- v3.7.1-rc 维护补丁：
  - fix(test)：WatchVersionGuardTest.versionBumpsOnWrite 改为真实
    versionOf 断言（原为空断言，维护期质量修复）；
  - docs(planning)：RFC-0001 v4 Multi-Model（Pending）+ ADR-0318；
  - 全量回归 0 failures。
- ADR-0006（RESP2）；依赖：Netty 4.1.115、AssertJ 3.26.3、exec 插件。
- Phase 2 内存引擎：StorageEngine SPI、64 段 SkipList MemTable、KeyValueEntry
  （版本 / tombstone / TTL / size）、跨段归并有序迭代器、MemoryManager
  （配额 + 淘汰回调接口）、TTLManager（惰性 + 主动混合过期）。
- `SET key value EX seconds | PX milliseconds` 支持。
- 存储测试套件（MemTable / StorageEngine / Delete / TTL / Iterator / 并发 /
  MemoryManager）与内存引擎基准（GET 10K/100K/1M，并发写 10/50/100 线程）。
- ADR-0007（SkipList）、ADR-0008（分段锁）、ADR-0009（TTL 混合策略）。
- Phase 3 热数据管理层：HotnessTracker / FrequencyCounter（LFU + 周期衰减）、
  LFUPolicy（快照索引 O(logN)）、ARCPolicy（T1/T2/B1/B2 + p 自适应原型）、
  EvictionManager、MigrationCallback、TrackingStorageEngine。
- 超内存配额触发淘汰：候选选择 → 迁移回调 → 物理移除；用户 DEL 保持 tombstone。
- 缓存测试套件（热度 / LFU / 衰减 / ARC / 淘汰 / 迁移 / 集成）与缓存基准。
- ADR-0010（热度跟踪）、ADR-0011（LFU 衰减）、ADR-0012（ARC 评估）。
- 迁移接口升级：`MigrationCallback` → `TierMigration`（SUCCESS / FAILED /
  RETRY），淘汰遵循"先迁移、后删除"（ADR-0013）。
- Phase 4 WAL 持久化层：WALManager / WALWriter / WALReader / LogSegment /
  SegmentManager / ChecksumValidator（CRC32C）/ RecoveryManager /
  CheckpointManager / WALStorageEngine。
- 写路径接入：WAL append（默认 EVERY_SEC）→ MemTable → ack；淘汰删除也落
  DELETE 记录；TTL 过期不落盘（由 PUT 推导）。
- 崩溃恢复：校验 → 重放 → 截断残尾；checkpoint（快照 + offset）加速；
  宕机期间过期的键不复活。
- WAL 测试套件（条目/校验/读写/轮转/恢复/崩溃三用例/检查点/集成）与
  WAL 基准（append 100K/1M、恢复 100K/1M）。
- ADR-0014（写策略）、ADR-0015（记录格式）、ADR-0016（崩溃恢复）。
- 基准口径修正：WAL append 指标标注为 buffered mode（非逐条 fsync），
  不等同 durable write throughput（Phase 4 评审）；技术债登记 TD-007/008。
- Phase 5 冷存储引擎：ColdStorageEngine（pending + 多表 + Manifest）、
  SSTableWriter / SSTableReader / Block / BlockIndex / BloomFilter /
  DiskIterator / CompactionManager / CompactionTask / FlushManager /
  ColdMigration。
- MemTable Flush（版本守卫）+ WAL checkpoint 接入；淘汰迁移写入冷层
  （pending → SSTable）。
- 冷存储测试套件（Bloom/SSTable/Flush/Compaction/Engine/迁移集成）与
  冷存储基准（写 100K/1M、随机 GET、FPR、合并）。
- ADR-0017（冷层策略）、ADR-0018（SSTable 格式）、ADR-0019（合并策略）。
- Phase 5 评审处置：SSTable 写吞吐改为 Peak/Average 双口径；登记 page cache
  影响与 cold-cache 基准计划（TD-009）；技术债 TD-010（pending 持久化）、
  TD-011（自动 Flush）、TD-012（leveled compaction）。
- Phase 6 自动调度：TieringController / WatermarkManager（70/85/95）/
  FlushScheduler（异步 + 去重 + 失败保留）/ MigrationScheduler +
  MigrationLog（持久化 + 启动恢复 + 幂等重放）/ BackPressureController /
  TierWorkerPool / StorageMetrics / TieringStorageEngine。
- EvictionManager 异步化：候选入队 → worker 写冷层 → WAL DELETE → 删内存；
  重试上限后 FAILED（内存保留）；CRITICAL 限写返回 -ERR。
- tiering 测试套件（水位/Flush/迁移队列/恢复/背压/控制器/集成）与
  tiering 基准（Flush、迁移 100K/1M、内存压力）。
- ADR-0020（调度模型）、ADR-0021（水位策略）、ADR-0022（迁移持久化）。
- Phase 6 评审处置：定位升级为「Redis 兼容 LSM 分层 KV 存储引擎」；登记
  TD-013（Immutable MemTable 轮转）、TD-014（迁移队列准入/批量/扩缩容）。
- Phase 7 并发优化：KeyShardExecutor / ShardRouter / ShardQueue / ShardWorker /
  ExecutionContext / ConcurrencyMetrics；CommandEngine.executeAsync +
  ResponseSequencer（RESP 保序）。
- MemTable 64 → 256 段；HotKeyDetector / AccessCounter / HotKeyPolicy /
  HotKeyReadCache / RequestCoalescer / HotKeyStorageEngine。
- 并发测试套件（路由/顺序/并行/读写/热点/合并/压力/异步保序）与并发基准
  （GET/SET/Mixed、热点 90%、分片对比）。
- ADR-0023（分片执行）、ADR-0024（MemTable 并发）、ADR-0025（热点缓解）。
- Phase 7 评审处置：定位升级为「高并发 Redis 协议兼容 LSM 冷热分层 KV
  存储引擎」；登记 TD-015（无锁读暂缓）、TD-016（Phase 9 三级基准）、
  TD-017（动态重分片）、TD-018（Hot Cache version check）。
- Phase 8 评审处置：能力矩阵 21 项确认；登记 TD-019（Phase 9 生产容量模型，
  停止 IO 微优化）；cold-cache 冷启动基准保持 TD-009。
- Phase 9 生产基准：三级基准套件（A/B/C）+ 管道 RESP 客户端；5 份报告
  （memory/server/production/capacity-model/deployment-profile）。
- 结论：A 级 GET 4.7M / SET 4.4M ops/s；B 级 pipeline64 峰值 218–231K
  （500K 目标未达，瓶颈=协议/调度）；C 级全链路 115–178K ops/s；
  Workload D 压力下内存受控、冷层落盘。
- ADR-0029（基准方法）、ADR-0030（容量模型）、ADR-0031（部署画像）。
- Phase 10 高级优化与生产化：响应批处理（ResponseBatcher/ResponseBuffer，
  batch=64 + 排空 flush）、回调式执行（去掉每请求 Future）、YAML 配置
  （TieringConfig + application.yaml）、MetricsRegistry + INFO 命令、
  ShutdownManager 优雅停机（stop accept → drain → WAL force + checkpoint）。
- 基准：Level B pipeline64×500 218–231K → 465K（>400K ✅）、pipeline128
  → 1.14M；Level C 154–326K 无回退。
- ADR-0032（响应批处理）、ADR-0033（内存模型）、ADR-0034（服务生命周期）。
- Phase 10 最终评审：确认 10 阶段路线图全部完成；定位为完整冷热分层存储
  系统（14 模块能力矩阵全 ✅）；最终评审归档 architecture-review。
- Phase 11 分布式集群：16384 hash slot 路由（HashSlotRouter /
  SlotTable / ShardGroup / ShardId / PartitionKey，CRC16/CCITT）、
  元数据服务（MetadataServer / ClusterMetadata / NodeRegistry /
  ShardRegistry / TopologyManager）、最小真实 Raft（RaftNode / RaftState /
  LogEntry / Term / VoteRequest/Response / AppendEntriesRequest/Response /
  LeaderElection / ReplicationManager：选举 + 心跳 + 日志复制 + commit/
  apply）、ReplicatedStorageEngine 复制适配器（写经 Raft 复制后 apply
  本地引擎，不改 MemTable/WAL/SSTable）、ClusterNode / ClusterClient。
- Phase 11 故障转移：leader 崩溃 → 新 leader 选举（≤310ms）；replica
  崩溃 → 半数存活继续服务；无多数派不提交；旧 leader 回归安全降级。
- Phase 11 测试：ShardingTest（10）/ MetadataTest（10）/ RaftTest（21）/
  FailoverTest（9）/ ClusterIntegrationTest（1，3 节点 SET → 复制 →
  杀 leader → 选举 → GET 正确），共 51 项新测试；全量回归 288 项全绿。
- Phase 11 基准（docs/benchmark/cluster-report.md）：单分片复制写
  154K ops/s（P99=0.027ms）、读 750K ops/s（P99=4μs）、路由开销
  ~23–36ns/op、复制滞后 ≤35ms、选举 124–310ms（目标 <5s ✅）。
- ADR-0035（哈希槽）、ADR-0036（Raft 元数据）、ADR-0037（Raft 复制）、
  ADR-0038（心跳 + 随机选举超时）。
- Phase 11 评审处置：登记 TD-022（Raft 日志持久化）、TD-023（TCP RPC
  传输）、TD-024（提交后立即补发 commitIndex 降低复制滞后）、TD-025
  （动态 slot 迁移）；技术债清单见 ROADMAP。
- Phase 11 外部评审归档（docs/review/phase11-cluster-review.md）：确认
  哈希槽 / 元数据 / Raft 修复 / 复制适配器设计正确；四项不足（Raft Log
  不持久化、Snapshot 缺失、进程内 RPC、Slot 迁移缺失）对应 TD-022/
  023/025 进入 Phase 12；元数据 Raft Cluster（etcd/PD 方向）纳入
  Phase 12 计划。
- Phase 12 分布式生产化：
  - RaftLog（FileRaftLog / LogSegment / RaftLogWriter / RaftLogReader /
    RaftLogRecovery：MAGIC/VERSION/TERM/INDEX/COMMAND_TYPE/DATA/CRC32C，
    SYNC/ASYNC/NONE，段滚动 + 尾部截断恢复）+ RaftPersistentState
    （term/votedFor/commitIndex 落盘，term 变更 force、commit 缓冲）；
  - Snapshot（SnapshotManager / SnapshotWriter / SnapshotReader /
    SnapshotMetadata：快照创建/加载/校验，RaftNode 超阈值自动压缩 +
    InstallSnapshot 追赶 + 重启"快照恢复 + 剩余日志重放"）；
  - Netty RPC（RpcServer / RpcClient / RpcCodec / RequestId /
    RaftMessageCodec：长度前缀帧 + 请求关联 + 超时 + 幂等重试 +
    连接复用；三类 Raft 消息）+ RaftTransport 抽象
    （LocalRaftTransport 测试回退 / NettyRaftTransport 生产）；
  - 复制优化（CommitNotifier 立即补发 commitIndex + ReplicationTracker /
    FollowerProgress：复制滞后 13–35ms → <1ms，目标 <5ms ✅）；
  - Slot 在线迁移（SlotMigrationManager / MigrationTask /
    MigrationCheckpoint / MigrationState：INIT→COPYING→VERIFYING→
    SWITCHING→DONE，checkpoint 持久化续传 + 源/目标 CRC 校验 + SlotTable
    原子切换 + 切换后清理源）；
  - StorageSnapshotCodec（存储引擎状态机快照编解码）。
- Phase 12 测试：RaftLog 21 / Snapshot 12 / RPC 19 / 迁移 11 /
  复制优化 5 / 快照集成 2 / TCP 集群集成 3（3 节点真实 TCP：
  SET→复制→杀 leader→选举→GET；重启恢复 term/commitIndex/数据；
  复制滞后 <100ms 断言）——新增约 73 项；全量回归最终统计见评审报告。
- Phase 12 基准（docs/benchmark/distributed-production-report.md）：
  RaftLog ASYNC append 102K ops/s（P99=27μs）、TCP 提交 1,359 ops/s
  （P50=0.65ms / P99=2.16ms）、复制滞后 0ms、RPC 9.3K ops/s
  （P50=100μs，单连接复用）、迁移 16.1MB/s + 断点续传 549ms/90K。
- ADR-0039（RaftLog 格式）、ADR-0040（快照策略）、ADR-0041（RPC 设计）、
  ADR-0042（复制滞后优化）、ADR-0043（Slot 迁移策略）。
- Phase 12 评审处置：TD-022~025 关闭；登记 TD-026（批量/并行复制）、
  TD-027（迁移单次迭代游标）、TD-028（RPC TLS/认证）、TD-029
  （元数据 Raft 化落地）。
- Phase 13 分布式优化：
  - Raft 批量/流水线复制：RaftReplicationConfig（maxBatchEntries /
    maxBatchBytes / flushInterval / maxInflight），异步批量发送 +
    group commit + inflight/lastSent 跟踪；日志镜像缓存消除持锁文件读；
  - 游标迁移：MigrationCursor（lastKey/lastVersion/checkpointOffset）、
    PAUSED 状态、`slot-{start}.cursor` CRC 游标文件、pause/resume/recover；
  - 安全 RPC：RpcSecurityConfig（TLS PEM 证书）+ RpcAuthInterceptor
    （Token 认证/过期 + AUTH/ERROR 帧）+ TokenBucket 限流（ERR RATE_LIMIT）；
    RpcClient 连接/重试全程非阻塞（修复事件循环阻塞导致的提交停滞）；
  - 元数据 Raft 化：MetadataRaftGroup / MetadataCodec / MetadataState
    （每副本独立状态机，修复共享状态交错问题）/ MetadataClient；
  - 跨机部署文档（docs/deployment/distributed-deployment.md）。
- Phase 13 测试：批量复制 15 / 迁移游标 15 / RPC 安全 19 / 元数据 Raft
  24 / 集成 5 / 基准 4 —— 新增 82 项；全量回归最终统计见评审报告。
- Phase 13 基准（docs/benchmark/phase13-report.md）：复制吞吐 9,220
  → 22,169 ops/s（目标 >5000 ✅）、迁移 216–245MB/s @1KB
  （目标 >100MB/s ✅）、RPC 安全开销 +50–70%、元数据故障转移
  115–290ms。
- ADR-0044（批量复制）、ADR-0045（游标迁移）、ADR-0046（RPC 安全）、
  ADR-0047（元数据 Raft）。
- Phase 13 评审处置：TD-026~029 关闭；登记 TD-030（MemTable 批量写）、
  TD-031（自适应 flush/异步客户端）、TD-032（token 签名轮换/mTLS）。
- Phase 14 生产加固：MemTable.applyBatch（批量写 + WAL 批量追加）、
  AdaptiveFlushController、ReplicationController + putAsync、HMAC-SHA256
  认证（防重放/轮换）+ mTLS、元数据 Raft 持久化（FileRaftLog +
  MetadataSnapshot）、跨机部署指南 + 故障注入测试（5/5）。
- Phase 14 测试：新增 101 项；基准：100B 迁移 18.3MB/s 与 Raft 37.3K
  ops/s 未达目标（已如实记录，TD-033/034），HMAC 开销≈0、元数据重启
  194ms。
- Phase 15 生产验证：
  - 流式迁移（ADR-0053）：StreamingMigrator 单次快照 + 跨批次持久
    scanner + MigrationStreamCursor（CRC/原地更新）+ 版本屏障 + 动态
    batch（100B→4096 / 1KB→1024 / 10KB→256）；修复每批重建 O(N)
    快照的隐藏 O(N²) 行为，100B 迁移 2.9 → 59.8 MB/s；
  - 全异步提案（ADR-0054）：RaftNode.proposeBatch（N 请求 → 单次
    AppendEntries）+ AsyncReplicationClient（有界队列 NORMAL/WARNING/
    CRITICAL 背压 + 内联批量 drain + leader 变更整批重试 ≤3）；
  - 证书生命周期（ADR-0055）：CertificateManager（加载/校验/过期/
    reload/原子轮换 + server/client supplier）+ CertificateWatcher
    （WatchService 监听 .crt/.key 变更）；
  - 混沌验证（ADR-0053~0056 支撑）：ChaosValidationTest 16 项（100ms
    延迟/5%/10% 丢包/follower/leader 分区/磁盘慢/leader 击杀/replica
    重启追平/混合故障/法定人数丢失），三轮稳定；发现并修复 Raft 缺陷
    ——冲突截断的未提交提案被同 index 新条目虚假完成
    （RaftNode.failPendingFromLocked + 回归测试）；
  - 集群可观测性（ADR-0056）：ClusterMetricsRegistry（raft_proposal_qps/
    raft_commit_latency/raft_replication_lag/migration_speed/
    migration_cursor/migration_remaining/certificate_expire_time）+
    ClusterInfo + `INFO CLUSTER`（node/role/term/leader/slot）；
  - INFO 命令扩展支持 `INFO [section]`（未知 section 返回错误）；
  - 测试：新增 98 项（迁移 19 / 异步 Raft 21 / 证书 15 / 混沌 16+1 /
    可观测性 15 / 基准 11）；全量回归 650 项全绿（Phase 1–15）；
  - 基准（docs/benchmark/phase15-production-validation-report.md）：
    迁移 100B 59.8 / 1KB 173.3 / 10KB 589.8 MB/s；Raft 1/64/256 写者
    129/259/331K ops/s（目标 100K/200K ✅），P99 0.009/3.071/9.824ms；
    混沌选举恢复 p50 155ms（目标 <5s ✅）；TLS 轮换 p50 13.5ms；
  - 未达标（如实记录）：100B/1KB 迁移未达 >100/>300 MB/s，瓶颈 = 写
    路径每条目 3 次数组拷贝（Mutation 构造/访问器 + KeyValueEntry），
    零拷贝批量写路径列入 Phase 16（TD-033）。
- Phase 16 Multi-Raft 架构演进：
  - Region 模型（ADR-0057）：Region（[startKey,endKey) + leader/peers +
    epoch + state）、RegionEpoch（confVer/version 从 1 起）、RegionManager
    （TreeMap 路由 + create/split/merge + tombstone 审计 + epoch guard +
    transferLeader）、StaleRegionEpochException；
  - Multi-Raft（ADR-0058）：MultiRaftNode（多 Raft 宿主）、RaftGroupManager
    （按 Region 创建/销毁组，含持久化组）、MultiRaftEndpoint（单端口共享
    RPC 端点 + [groupId] 前缀路由）、MultiRaftTransport（RaftTransport
    兼容，RaftNode API 零改动）；
  - 零拷贝批量写（ADR-0059）：RawMutation（所有权转移，不克隆）、
    KeyValueEntry record→class + owned 构造、MemTable.applyRawBatch
    （平面桶分组 + 单段单锁 + 版本按序分配）、SkipList.putAndGetOld
    （单次查找）、StreamingMigrator 切换零拷贝路径、全槽位迁移跳过
    slot 哈希、游标 advance 去克隆；
  - 放置控制（ADR-0060）：PlacementManager（distribution/balanceSkew/
    isBalanced/transferLeader），自动 rebalance 暂缓；
  - Region 可观测性：RegionMetricsRegistry（region_count/region_size/
    region_split_count/raft_group_count/leader_distribution/
    region_move_bytes）+ RegionInfo + `INFO REGIONS`；
  - 混沌验证：ChaosClusterTest 20 项（多 Region 延迟/丢包/分区/磁盘慢/
    双组击杀/重启追平/混合故障/epoch 保护）；
  - 缺陷修复：新 leader 以非空日志当选后不回填滞后 follower
    （心跳拒绝未回退 nextIndex → 修复 + 回归测试
    newLeaderBackfillsLaggingFollowerWithoutNewWrites）；
  - 跨机部署：ClusterMain（3 JVM 拓扑入口）+ deploy/Dockerfile +
    docker-compose.yml + chaos-netem.sh + 跨机指南；
  - 测试：新增 138 项（Region 34 / Multi-Raft 32 / Zero-Copy 21 /
    Chaos 21 / Placement+可观测性 23 / 基准 6）；
  - 全量回归 788/788 全绿（Phase 1–16）；
  - 基准（docs/benchmark/phase16-multiraft-report.md）：零拷贝迁移
    100B 82.7 / 1KB 223.1 / 10KB 631.0 MB/s；Multi-Raft 1/2/4 组
    110/222/404K ops/s（线性扩展 2.02×/3.68× ✅）；TCP 单端口多组
    P99 0.551ms；故障恢复 p50 183ms；
  - 未达标（如实记录）：100B/1KB 迁移未达 >100/>300 MB/s
    （82.7/223.1，剩余每条目固定开销 → Phase 17 并行迁移）。
- Phase 17 Region 生命周期与分布式存储完善：
  - Region Split（ADR-0061）：SplitController（PREPARE/SNAPSHOT/INSTALL/
    COMMIT/CLEANUP）+ SplitSnapshot（CRC/屏障）+ SplitWriteBuffer
    （窗口写不丢失）+ epoch+1 + 路由原子切换；
  - Region Merge（ADR-0062）：MergeController（PREPARE→LOCK→TRANSFER→
    UPDATE_META→TOMBSTONE），右→左零拷贝搬迁，失败状态可重置重试；
  - 并行迁移（ADR-0063）：RegionTransferManager + MigrationChunk +
    ChunkWorker + ChunkCheckpoint（CRC/retry/pause-resume）+
    MemTable.segmentIterator 按段分片；100B 209.1MB/s（>150 ✅）；
  - 真实 leader 交接（ADR-0064）：RaftNode.transferLeadership +
    TimeoutNow RPC（三类传输）+ receiveTimeoutNow 立即选举 +
    LeaderTransferManager；24ms（<500ms ✅）；
  - Redis Cluster Gateway：GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS +
    MOVED slot host:port；GET 3.68M / SET 1.67M ops/s；
  - 自动均衡（ADR-0065）：BalanceScheduler 检测 region/leader/disk/cpu
    压力生成 BalancePlan（epoch 保护，不自动执行危险迁移）；
  - 可观测性：RaftMetricsRegistry + MigrationMetricsRegistry +
    RegionMetricsRegistry.recordMerge + INFO RAFT / INFO MIGRATION；
  - 混沌：RegionChaosTest（分裂 10000 并发写无丢失、合并故障恢复、
    200ms 延迟 + 10% 丢包交接、Region 隔离、旧纪元拒绝）；
  - 测试：新增 159 项；全量回归 947/947 全绿（目标 ≥900 ✅）；
  - 基准（docs/benchmark/phase17-region-report.md）：Split 1M≈0.9s、
    Merge 1M≈0.7s、并行迁移 209.1MB/s、Transfer 24ms、Gateway 全达标。
- Phase 18 分布式生产集成：
  - 统一路由（ADR-0066）：RoutingTable（键范围+slot 区间+epoch+leader+
    raftGroup）+ RoutingCache（陈旧自刷新）+ RouteEpochGuard +
    MOVED/ASK/TRYAGAIN 统一；
  - 真实 TCP 网关（ADR-0068）：NettyClusterGateway + 批量 flush
    （GET 719K / SET 590K ops/s）+ CLUSTER SLOTS/NODES；
  - Split/Merge 与 Raft 联动（ADR-0067）：RegionRaftMigrationManager
    （子/合并组 + 路由原子切换 + 回滚/恢复幂等）；
  - 生产化迁移：ByteRateLimiter + MigrationScheduler + 迁移指标
    （remaining/error）；
  - 跨节点部署（ADR-0069）：docker-compose.cluster.yml +
    CrossMachineChaosTest（20 项）；
  - 可观测性（ADR-0070）：MetricsExporter（Prometheus）+
    ProductionInfo（INFO CLUSTER 聚合）；
  - 测试：新增 165 项；全量回归 1112/1112 全绿（目标 >1100 ✅）；
  - 基准（docs/benchmark/phase18-production-report.md）：Gateway
    719K/590K ops/s、迁移 209.1/986.0 MB/s、Split/Merge 1M ~0.9/~0.7s。
- Phase 19 MVCC 与事务引擎：
  - MVCC（ADR-0071）：MvccKey/MvccEntry/MvccStorageEngine（内存版本索引
    + 启动重建）+ SnapshotReader（commitTS <= readTS）；
  - 时间戳（ADR-0072）：TimestampOracle + HybridLogicalClock（回拨安全）；
  - 事务（ADR-0073）：Percolator 2PC + Transaction/TransactionManager/
    Coordinator（参与者键归属 + 部分 prewrite 回滚）+ TxnJournal（Raft）；
  - 锁/冲突（ADR-0074）：LockTable + ConflictDetector + 三类异常；
  - 恢复（ADR-0076）：超时回滚/primary 补完/无永久锁；GC（ADR-0075）：
    SafePoint + 保留最新；
  - 指标：INFO TRANSACTION + Prometheus（txn_* / mvcc_*）；
  - 一致性修复（ADR-0077）：空心跳 commitIndex 上界 = 已校验前缀，
    修复旧 leader 未提交冲突条目被心跳虚假提交的共识缺陷；
  - 测试：新增 227 项；全量回归 1339/1339 全绿（目标 >1290 ✅）；
  - 基准（docs/benchmark/phase19-mvcc-report.md）：GET 3.1–4.7M ops/s、
    单区事务 70.8–204.6K txn/s、冲突 2.1–7.6M ops/s、GC 19–29MB/s
    （未达 100，TD-041 如实登记）。
- Phase 20 事务生产化与存储优化：
  - 批量 GC（ADR-0078）：BatchGcExecutor（索引规划 + 分段批量物理删除 +
    并行 worker + gc.batch.size/worker.count/max.memory 配置），
    107–285MB/s（>100 ✅，TD-041 关闭）；
  - 网关自动事务（ADR-0079）：GET 快照读 / SET/DEL 单键事务 /
    MGET 一致快照 / MSET 跨 shard 2PC，RESP 不变（TD-042 关闭）；
  - 持久化 MVCC 索引（ADR-0080）：Writer/Reader/Snapshot + 增量重建；
  - 事务日志（ADR-0081）：PersistentTxnJournal（本地落盘 + Raft 提案
    重试）+ TxnRecoveryReplay（COMMIT 补完 / ROLLBACK 清理 / 幂等）；
  - 锁过期修复：LockRecord 改用墙上时钟，修复 HLC 尺度错配；
  - 可观测性：INFO TRANSACTION/MVCC + Prometheus（txn_abort/recovery、
    mvcc_versions/gc_deleted、redis_txn_latency）；
  - 测试：新增 181 项；全量回归 1523/1523 全绿；
  - 基准（docs/benchmark/phase20-report.md）：GC 107–285MB/s、
    网关 GET 2.0–6.9M / SET 141–389K ops/s、单区事务 324–651K txn/s、
    跨区 62–158K txn/s、恢复 1–4ms，全部达标；
  - 跨机验证（ADR-0082）：Docker daemon 可用；容器内 Maven 网络受限，
    跨机 tc netem 未执行，登记 TD-040/TD-043，本地混沌 30 项兜底。
- Phase 21 分布式事务网络化与云生产：
  - 分布式事务路由（ADR-0083）：DistributedTxnRouter / RegionTxnClient /
    TxnParticipantClient，PREWRITE/COMMIT/ROLLBACK/HEARTBEAT 复用
    MultiRaftEndpoint 单端口 RPC；TransactionParticipant 幂等状态机；
  - 事务元数据 Raft（ADR-0084）：TransactionMetadataService +
    TxnMetadataRaftGroup，Coordinator 崩溃恢复续跑；
  - MVCC 在线压缩（ADR-0085）：MvccCompactor（SafePoint 合并 + 原子索引文件）；
  - 真实跨机混沌（ADR-0086）：Docker 三节点 + tc netem（100ms/5%/2%）
    + 分区 + kill -9，全部存活恢复；修复容器构建三缺陷
    （netty classifier / Main-Class / fat jar）；
  - 可观测性：txn_prepare/network_retry/lock_wait/region_count/recovery_time、
    mvcc_compaction_*；
  - 测试：新增 202 项；全量回归 1725/1725 全绿；
  - 基准（docs/benchmark/phase21-report.md）：单区 58.7–116.4K、
    多区 88.1–110.7K txn/s、恢复 0–0ms、leader 恢复 156–276ms。
- Phase 22 事务可靠性与生产运行时：
  - 决策排序（ADR-0087）：decisionIndex + Raft-first，恢复补完 COMMITTED；
  - 生命周期（ADR-0088）：TTL/心跳/超时自动 abort（txn.ttl-seconds）；
  - 锁解析（ADR-0089）：LockResolver + TxnStatusCache；
  - 运行时（ADR-0090）：TCP 端到端 + participant 重启恢复；
  - 指标：txn_expired/long_running/abort_reason/lock_total/resolve_total；
  - 测试：新增 124 项；全量回归 1849/1849 全绿；
  - 基准（docs/benchmark/phase22-report.md）：SET 128–150K、
    GET 3.9–25M、跨区 33.6–59.7K、恢复 0–15ms、锁解析 50–129ms。
- Phase 23 事务运行时最终化：
  - runtime 角色（gateway/coordinator/participant/metadata）+
    docker-compose.transaction.yml（ADR-0093）；
  - 生命周期持久化（ADR-0091）：TxnLifecycleRecord + MetadataRaft；
  - LockResolver RPC（ADR-0092）：CHECK/RESOLVE/HEARTBEAT；
  - 磁盘混沌（ADR-0094）：disk full/readonly/slow 零提交丢失；
  - 测试：新增 158 项；全量回归 2007/2007 全绿；
  - 生产配置冻结：docs/deployment/production-runtime.md。
- Phase 9 评审处置：确认瓶颈分层（A 4.7M → B 230K → C 150K，瓶颈=协议/调度）；
  登记 TD-020（request→response 对象数优化）与 TD-021（JFR 验收指标）。
- Phase 8 IO 优化：MmapSSTableReader（零拷贝块读）+ FileChannel baseline、
  MemoryPool（DirectBuffer 大小类池 / BufferArena / BufferRecycler /
  AllocationTracker）、BlockCache（LRU + 池化缓冲 + invalidate/clear）、
  IOStatistics；ColdStorageEngine 默认 mmap + cache。
- IO 测试套件（mmap/baseline/cache/pool/恢复）与 IO 基准（mmap vs
  FileChannel、缓存冷热混合、内存/GC 概况）。
- ADR-0026（SSTable IO 策略）、ADR-0027（Off-Heap 策略）、
  ADR-0028（Block Cache 策略）。

### Changed

- 目录结构与标准框架对齐；ADR-0002 更名为 ADR-0002-storage-engine.md；
  architecture.md 拆分为 overview + 三个分主题架构文档。
- src/main 骨架目录统一为 Maven 标准布局 `src/main/java/io/tieringkv`
  （TD-004 关闭）。
- 修复：Netty 管道顺序（Encoder 位于 pipeline 最前，符合出站事件方向）；
  ByteProcessor 扫描语义修正。
- 根据 Phase 1 评审修正定位措辞：README 明确为「RESP 兼容 KV Server 基础层」，
  性能与分层能力列为演进目标；评审意见归档至
  docs/review/architecture-review.md。
- Command 层迁移至 StorageEngine；移除 KVStore / InMemoryKVStore（Phase 1
  占位实现，由 MemTable 取代）。
- surefire 测试堆配置 `-Xmx1g`（支持 1M 数据集基准）。
- 基准报告标注口径：存储层 GET baseline（P99≈2.5μs）与网络端到端
  （P99≈0.19ms）分离，避免口径混淆（Phase 2 技术评审）。

## [0.1.0] - 2026-08-09

### Added

- 初始化 Git 仓库（main + develop 分支）与完整目录骨架。
- 新增 README.md、ROADMAP.md、CHANGELOG.md、.gitignore。
- 新增 Maven 构建骨架（Java 17、JUnit 5），`mvn test` 可验证。
- 新增 ADR-0001（项目总体架构）、ADR-0002（存储引擎策略）、ADR-0003（并发模型）。
