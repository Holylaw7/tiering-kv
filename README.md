# Tiering-KV

> 高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎
> （RESP + WAL + MemTable + SSTable + 自动调度 + Key Sharding +
> Raft 持久化集群 + 批量复制 + 安全 RPC + 元数据 Raft + 游标迁移）。

**阶段状态：Phase 44（真实执行门禁闭环与全球规模最终化）✅（Phase 0–43 全部完成 ✅，v2.7.0）**

## 项目定位

**当前定位**：高并发 Redis 协议兼容的 LSM 冷热分层 KV 存储引擎——已完成 RESP
协议、内存引擎、LFU/ARC 淘汰、WAL 持久化、SSTable 冷层、自动 Flush /
异步迁移 / 背压、Key Sharding 异步执行与热点治理（Phase 1–10），并完成
分布式集群基础：16384 hash slot 路由、元数据服务、最小真实 Raft（选举 /
心跳 / 日志复制 / 提交）与故障转移（Phase 11），以及分布式生产化：
Raft 日志持久化 + 快照、Netty TCP RPC、复制滞后优化（<1ms）、在线
Slot 迁移（Phase 12），以及分布式优化：批量/流水线复制（>5000 ops/s）、
游标迁移、TLS/认证/限流安全 RPC、元数据 Raft 化（Phase 13），以及生产
加固：MemTable 批量写、自适应 Flush/复制、异步提案、HMAC/mTLS、
元数据持久化与故障注入（Phase 14），以及生产验证：流式迁移（单次快照 +
游标 + 版本屏障）、全异步批量提案（129–331K ops/s）、证书生命周期自动
轮换、混沌验证（16 项，发现并修复 Raft 截断提案虚假完成缺陷）、集群
可观测性（INFO CLUSTER）（Phase 15）；面向 redis-cli 与主流客户端提供
PING / ECHO / SET / GET / DEL / EXISTS 能力；以及 Multi-Raft 架构演进：
Region 抽象（键范围 + epoch 路由保护）、每 Region 独立 Raft 组
（单端口共享传输 + 组隔离）、零拷贝批量写（RawMutation 所有权转移）、
放置控制（分布/均衡/leader 转移）、多 Region 混沌验证（发现并修复
滞后副本回填缺陷）（Phase 16）；以及 Region 生命周期闭环：自动分裂/
合并（写缓冲保证并发写不丢失）、并行迁移（100B 209MB/s）、真实 Raft
领导权交接（TimeoutNow，24ms）、Redis Cluster 网关（MOVED + CLUSTER
SLOTS）、自动均衡计划（epoch 保护）（Phase 17）；以及分布式生产集成：
统一 Region/Slot 单路由模型（epoch 守卫 + 缓存自刷新）、真实 TCP
Redis Cluster 网关（GET 719K / SET 590K ops/s）、Split/Merge 与 Raft
组联动（子组独立日志 + 回滚）、生产化迁移（限速 + 自适应调度 +
Prometheus 指标）、三节点容器部署产物与跨机混沌（Phase 18）。
以及数据库内核：MVCC 多版本模型 + HLC 时间戳 + Snapshot Read +
Percolator 2PC 事务（Prewrite/Commit/Rollback）+ 锁与冲突检测 +
事务恢复 + MVCC GC + 跨 Region 2PC + Raft 事务日志（Phase 19）。
以及事务生产化：事务生命周期（TTL/心跳/超时 abort）+ 锁解析 +
事务 RPC 网络化（Phase 21）+ 生产运行时角色 + 磁盘混沌（Phase 22）
+ 生命周期持久化 + 分布式锁解析 RPC + 事务运行时最终化（Phase 23）；
以及云原生生产发布：事务元数据 Multi-Raft（ADR-0095）、健康探针与
优雅停机（ADR-0096）、备份/恢复（ADR-0097）、滚动升级（ADR-0098）、
Kubernetes 生产清单、CI 容器 E2E 工作流，全量测试 2238/2238 全绿
（Phase 24，v1.0）。
以及控制面 GA 闭环：事务元数据 Multi-Raft 网络化（TD-050 关闭，
Coordinator → Netty RPC → 三节点元数据组 + 持久化日志/快照）、异步
RPC 响应、CI 容器故障注入 / 真实块设备混沌 / kind 集群内验证交付物
（TD-048/049 Runner 待执行），全量测试 2408/2408 全绿（Phase 25，
v1.0.0 GA 候选）。
以及 v1 发布冻结：RESP2/RPC v1/存储格式 v1（ADR-0103）+ PITR 时间点
恢复（ADR-0104）+ CDC exactly-once 流式变更（ADR-0105）+ 企业安全
RBAC（ADR-0106）+ Kubernetes Operator（ADR-0107）+ tierctl CLI +
v1 发布流水线；全量测试 2701/2701 全绿（Phase 26，v1.0.0 发布候选）。
以及跨地域复制（async/sync + 冲突标记）+ Geo 分布式事务（决策日志恢复）
+ RBAC 网关/RPC 接线 + PITR 保留策略 + CDC 多消费者组 + SQL/Vector/
SaaS 探索原型；全量测试 2965/2965 全绿（Phase 27，v1.1.0 方向）。
以及多主复制：双向管道（VersionVector 环回抑制 + CRDT 收敛）+ 两地
三中心容灾（切换计划 + RTO/RPO 演练）+ SQL 引擎（Hash Join/聚合/
GROUP BY）+ HNSW 与混合检索 + SaaS 多租户控制平面 + RPC 帧级令牌；
全量测试 3216/3216 全绿（Phase 28，v1.1.0）。
以及分布式查询与地域规模：分布式 SQL（分片计划 + 两阶段聚合）+ 向量
分片（路由 + 重平衡计划）+ Geo CRDT 规模验证与时钟校准 + 三地五中心
与全球一致性读 + SaaS 计量/市场 + 分布式告警 + v1.2 发布流水线；
全量测试 3471/3471 全绿（Phase 29，v1.2.0）。
以及动态重分片与全球运维：版本化路由（双写/切换/回滚）+ 向量分片迁移
+ SQL 写事务 + 全球读水位联动（陈旧度分位）+ SaaS 账单导出/周期结算
+ 谓词下推/结果缓存 + 容量模型 + v1.3 发布流水线；全量测试
3742/3742 全绿（Phase 30，v1.3.0）。
以及自治运维与全球多活：负载驱动自动重分片（熔断）+ SQL 写 2PC 端到端
+ 向量双写迁移 + 全球 Active-Active（环回抑制 + 冲突合并）+ 账单周期
滚动结算 + 多云部署/迁移 + 企业控制台 API + v1.4 发布流水线；全量测试
4000/4000 全绿（Phase 31，v1.4.0）。
以及生产接线与全球验证：SQL 写 2PC 生产执行 + 控制台 REST 服务 +
并发自动重分片 + 网关冲突审计 + 全局多活自动选主 + 数据主权合规 +
v1.5 发布流水线；全量测试 4251/4251 全绿（Phase 32，v1.5.0）。
以及 SaaS 商业化与自治运维：SQL 写 2PC 真实协调器端到端（决策日志 +
跨地域 prewrite/commit）+ 选主与 Raft term 联动（低 term 防脑裂）+
控制台 UI 原型（RBAC 门控）+ SaaS 商业化闭环（订阅状态机 + 市场目录 +
周期计费）+ AI 驱动容量规划（趋势预测 + 置信带 + 风险等级）+ 数据网格
联邦查询（域目录 + 跨域聚合/JOIN + 域隔离）+ 全球多活流量治理（地域
配额 + 优先级降级）+ v1.6 发布流水线；全量测试 4570/4570 全绿
（Phase 33，v1.6.0）。
以及 SaaS 产品化与自治运维闭环：控制台 SaaS 产品化（仪表盘/市场/订阅
视图 + RBAC API）+ AI 自治闭环（容量护栏 + 流量限幅/熔断/回滚）+
跨云数据网格联邦（数据主权联动）+ 法规合规自动化（法规映射 + 审计导出
+ 合规报告）+ 企业级可观测性（跨 RPC 追踪 + 成本归因）+ 商业化运营指标
（MRR/试用转化/流失告警）+ v1.7 发布流水线 + JVM 级生产门禁；全量测试
4926/4926 全绿（Phase 34，v1.7.0）。
以及全球 AI 自治与合规即代码：全球多活受限自治（容量/流量/重分片联动 +
策略围栏：日预算/地域上限/熔断/回滚）+ 跨云实时物化视图（预聚合 + stale
标记）+ 合规即代码（法规版本化 + 持续审计流水线）+ Workload 感知成本
优化（缩容/冷层/压缩 + 收益/风险）+ 多租户网络隔离（默认拒绝 + 白名单）
+ SLA/SLO 管理（滚动窗口 + 违约告警）+ v1.8 发布流水线 + JVM 级生产门禁；
全量测试 5286/5286 全绿（Phase 35，v1.8.0）。
以及门禁收敛与自学习自治：真实执行门禁收敛表 v2 + JVM 级门禁扩展 +
全球自治自学习围栏（成功放宽/失败收紧/回滚熔断 + 上下界 + 审计）+
物化视图 CDC 增量刷新（INSERT/UPDATE/DELETE + 回退全量）+
合规持续证明（SHA-256 哈希链 + 篡改检测）+ 多云成本竞价调度（最低成本
+ 主权/配额/SLO 约束）+ 租户网络策略即代码（声明式 DSL + 幂等编译）+
SLO 预算驱动容量决策（达成率 → 扩容建议）+ v1.9 发布流水线；全量测试
5660/5660 全绿（Phase 36，v1.9.0）。
以及多目标自治与跨云物化（v2.0 GA）：真实执行门禁收敛表 v3 + JVM 级
门禁扩展 + 自学习围栏多目标优化（成本 × 风险 × SLO 加权）+ 跨云远端
物化存储（远端落盘 + CDC 增量同步 + 主权拒绝）+ 合规证明跨机构验证
（独立验证 + JSON 导出）+ 多云 spot 竞价（中断感知期望成本）+ 网络策略
跨租户审计（编译联动 + 视图聚合）+ 多 SLO 预算自动谈判（加权缺口 +
最差优先）+ v2.0.0 GA 发布流水线；全量测试 6040/6040 全绿
（Phase 37，v2.0.0）。
以及生产收敛与自治智能：真实执行门禁收敛表 v4 + JVM 级门禁扩展 +
远端物化增量状态持久化（CRC 落盘 + 损坏回退，TD-064 关闭）+ 全球自治
策略自进化（简化 Q 学习）+ 物化视图生命周期（TTL + 归档恢复）+
合规证明公钥签名（HMAC 覆盖完整 payload）+ Spot 中断迁移自动化
（确定性迁移计划）+ 网络策略风险评分（规则驱动 0~100 + 风险视图）+
v2.1.0 发布流水线；全量测试 6433/6433 全绿（Phase 38，v2.1.0）。
以及多智能体自治与生产验证：真实执行门禁收敛表 v5 + JVM 级门禁扩展 +
强化学习多智能体自治（本地 Q + 联邦聚合）+ 物化视图远端自动分层
（热度 → HOT/WARM/COLD）+ 合规证明链上锚定（SHA-256 + 篡改检测）+
Spot 市场实时预测（移动平均/指数平滑）+ 策略风险自适应加固（评分驱动
撤销 + 审计回滚）+ 全局 Pareto 容量优化（支配关系 + 权重选择）+
v2.2.0 发布流水线；全量测试 6878/6878 全绿（Phase 39，v2.2.0）。
以及拓扑感知自治与对象存储收敛：真实执行门禁收敛表 v6 + JVM 级门禁
扩展 + 多智能体分层联邦学习（拓扑分组 → 分层聚合）+ 物化视图冷层对象
存储归档（S3 兼容 + 主权拒绝）+ 合规证明跨链互操作（多链锚定 + 一致性
验证）+ Spot 市场实时竞价（价格/中断率约束出价）+ 自适应加固策略学习
（风险反馈 → 阈值自进化）+ 全局 Pareto 动态重平衡（指标流 → 前沿更新
+ 限幅）+ v2.3.0 发布流水线；全量测试 7360/7360 全绿
（Phase 40，v2.3.0）。
以及真实集成收敛与生产加固：真实执行门禁收敛表 v7 + JVM 级门禁扩展 +
真实 S3 API 接入（put/get/delete + 模拟 fallback）+ Spot 市场真实
数据源（真实/模拟切换）+ 签名密钥轮换（双密钥原子切换 + 宽限期 +
回滚）+ 物化视图对象存储生命周期联动（TTL → 过期规则 + 恢复保护）+
生产级 LSM 演进（leveled compaction 计划 + Immutable MemTable 轮转）+
PD 等价调度（放置约束 + 均衡计划 + 配额限流）+ v2.4.0 发布流水线；
全量测试 7855/7855 全绿（Phase 41，v2.4.0）。
以及执行收敛与事务深度：真实执行门禁收敛表 v8 + JVM 级门禁扩展 +
Leveled Compaction 执行（合并 + tombstone + TTL + 层级落盘）+
悲观事务（提前加锁 + 冲突 + 死锁超时）+ Async Commit + resolved-ts
（单区一阶段 + 回退 2PC + 单调水位）+ Coprocessor SQL 下推（FILTER/
PROJECT/AGGREGATE）+ 自治 PD 调度（护栏内执行 + 熔断）+ 全球拓扑
自发现（心跳分组 + 故障剔除）+ v2.5.0 发布流水线；全量测试
8357/8357 全绿（Phase 42，v2.5.0）。
以及全球规模与生产基线收敛：真实执行门禁收敛表 v9（ADR-0213，可执行项
JVM 全绿 + 未执行项精确登记）、跨区一阶段提交（ADR-0214，TD-079 关闭
方向，主副本资格 → 一阶段 / 回退 2PC）、Coprocessor 多算子联合下推
（ADR-0215，TD-080 关闭方向，FILTER → PROJECT → AGGREGATE 链）、TSO
集群化（ADR-0216，批量分配 + 单调 + 恢复不回退）、自治 PD 与全球自治
联动（ADR-0217，拓扑变化 → 计划 → 政策/地域/AZ 护栏 → 回滚 + 审计）、
生产级 Benchmark 基线（ADR-0218，A/B/C 三级 + TiKV 对比口径）、真实
凭据验证（ADR-0218，S3/Spot 三模式 + 降级登记，TD-076 关闭方向）、
v2.6.0 冻结与发布流水线（ADR-0219）；全量测试 ≥8867 全绿
（Phase 43，v2.6.0 发布候选）。
以及真实执行门禁闭环与全球规模最终化：真实执行门禁收敛表 v10
（ADR-0220，可执行项 JVM 全绿 + 未执行项精确登记）、全局一阶段提交
规模化（ADR-0221，TD-079 规模化，3 地/5 地 + 回退 2PC +
resolved-ts 联动）、Coprocessor 全算子联合下推（ADR-0222，TD-080
规模化，JOIN/GROUP_BY/ORDER_BY/LIMIT 固定链）、TSO 跨地域容灾
（ADR-0223，主备切换 + 恢复不回退）、自治 PD 全自动（ADR-0224，
风险分级 + 自动执行 + 人工熔断）、TiKV 对比基线 + 真实凭据 v2
（ADR-0225，A/B/C/D 四级 + 真实 HTTP 探针，TD-076 关闭方向）、
v2.7.0 冻结与发布流水线（ADR-0226）；全量测试 ≥9412 全绿
（Phase 44，v2.7.0 发布候选）。

**边界（如实声明）**：仍为教学/工程级实现，暂不宣称"高性能 Redis 替代品"；
分布式为真实 TCP + 持久化原型，基准以进程内为主，跨机 `tc netem` 验证
待 Linux+Docker 环境执行（部署产物已交付）；100B/1KB 零拷贝迁移
（82.7/223.1 MB/s → 并行 209.1/986.0 MB/s）已达标；网关 CLUSTER 命令
为子集；split/merge 与独立 Raft 组数据搬迁已联动；跨机容器混沌待
Linux+Docker 执行（产物已交付）；pub/sub、Lua、RESP3 与正式性能基线
（内存降低 60%–80%）为后续演进方向；Phase 24 元数据 Multi-Raft 为进程内
传输（TD-050）；Phase 25 已网络化并关闭 TD-050；真实 Docker 磁盘混沌与
CI 容器 E2E 的 Runner 执行仍待触发（TD-048/049，脚本/工作流/门控测试
已交付）。

## 核心能力

1. Redis RESP 协议兼容
2. 内存 + 磁盘冷热分层存储
3. LFU / ARC 数据热度管理
4. 异步冷热迁移
5. LSM-Tree / Bitcask 持久化
6. 高并发网络模型
7. mmap 零拷贝优化
8. 分段锁 / 无锁数据结构
9. Bloom Filter 防缓存击穿
10. 自研 Memory Pool

## 总体架构

```text
Client
  │
  ▼
RESP Protocol
  │
  ▼
Network Layer
  │
  ▼
Command Engine
  │
  ▼
Memory Tier (MemTable)
  │
  ▼
Hotness Manager
  │
  ▼
Cold Storage
  │
  ▼
Bitcask / LSM Tree
```

横切模块：WAL、Scheduler（异步迁移）、Metrics、Eviction（LFU/ARC）、Compaction、
Bloom Filter、Memory Pool。

代码组织为 `io.tieringkv` 根包下的模块分包：`network`、`protocol`、`command`、
`storage`、`memory`、`cache`、`eviction`、`wal`、`sstable`、`compaction`、
`scheduler`、`metrics`、`benchmark`。跨层只允许依赖接口，禁止反向依赖
（见 [ADR-0001](docs/adr/ADR-0001-project-architecture.md)）。

## 内存引擎架构（Phase 2）

```text
Command Layer
     │
     ▼
StorageEngine（SPI）
     │
     ▼
MemTable（64 段 SkipList + 分段读写锁）
     ├── KeyValueEntry（版本 / tombstone / TTL / size）
     ├── MemoryManager（配额 + 淘汰回调接口）
     └── TTLManager（惰性 + 主动混合过期）
```

- 有序键空间与有序迭代 → 为 LSM / SSTable 生成准备（ADR-0007）；
- 64 段分段锁替代全局锁（ADR-0008）；
- DELETE 使用 tombstone；TTL 惰性 + 主动清扫（ADR-0009）；
- `SET key value EX seconds | PX milliseconds` 已支持。

## 热数据管理层（Phase 3）

```text
Command Layer
     │
     ▼
TrackingStorageEngine（装饰器：产生 AccessEvent）
     │
     ▼
EvictionManager
     ├── LFU（默认：频率 + 周期衰减）
     ├── ARC（原型：T1/T2 + B1/B2 ghost）
     └── MigrationCallback（Phase 4/6 接冷存储）
```

- 每次 GET / SET / DELETE 产生访问事件，热度数据驱动淘汰决策；
- LFU 频率按可配置周期衰减（×0.5，懒计算）；
- 超内存配额 → 选候选 → 迁移回调 → 物理移除；用户 DEL 仍走 tombstone。

## 持久化层（Phase 4，WAL）

```text
Command → WALStorageEngine
    ├── WALManager（append / flush / rotate / checkpoint）
    ├── RecoveryManager（启动恢复：校验 → 重放 → 截断残尾）
    └── MemTable
```

- 写路径：WAL append（默认 EVERY_SEC，缓冲模式，≤1s 丢失窗口）→ MemTable
  → ack；ALWAYS 提供逐条 fsync 强一致选项；
- 记录格式：MAGIC / VERSION / TYPE / 时间戳 / 长度 / TTL / 版本 + CRC32C
  （ADR-0015，禁用 Java 序列化）；
- segment 滚动（`wal/%06d.log`，64MB）+ checkpoint（快照 + offset）加速恢复；
- 恢复时按绝对过期点判定 TTL，宕机期间过期的键不复活。

## 冷存储架构（Phase 5，SSTable / LSM）

```text
WAL → MemTable（热层）→ Flush → SSTable（冷层）
    → Manifest + Compaction；读取：pending → 新表 → 旧表
```

- SSTable：Data Blocks（4KB，CRC32C）→ Index Block → Bloom Block → Footer；
- 随机读：Bloom → Index 二分 → Block 解码 → 块内二分；
- 淘汰迁移：EvictionManager → ColdMigration → pending 缓冲 → 阈值落 SSTable；
- 合并：size-tiered 触发 + 全量 latest-wins（重复键 / tombstone / 过期 TTL）。

## 自动调度架构（Phase 6）

```text
Command → TieringStorageEngine（背压 + 水位）
    → TieringController
        ├── WatermarkManager（70% / 85% / 95% + 队列阈值）
        ├── FlushScheduler → 后台 Flush Worker → SSTable
        ├── MigrationScheduler → MigrationLog → 后台 Worker → ColdStorage
        └── BackPressureController（CRITICAL 限写，超时 -ERR）
```

- 自动 Flush：写后水位检查触发，后台执行、去重、失败保留重试；
- 异步迁移：EvictionManager 入队 → worker 写冷层 → WAL DELETE → 删内存；
  状态持久化到 `migration/migration.log`，启动恢复未完成任务；
- 指标：StorageMetrics 覆盖内存 / 迁移 / Flush / 冷层。

## 并发架构（Phase 7）

```text
Netty EventLoop → CommandEngine.executeAsync → KeyShardExecutor
    → ShardRouter（fnv1a % N）→ ShardQueue → ShardWorker → StorageEngine
    → ResponseSequencer（每连接按序号释放响应）
```

- 同键 FIFO 有序、异键并行；RESP 响应顺序不被并行破坏；
- MemTable 256 段分段锁；热点读走 HotKeyReadCache（无锁子集 + 请求合并）；
- ConcurrencyMetrics 观测队列深度 / 分片利用率 / 等待 / 延迟。

## IO 架构（Phase 8）

```text
GET → ColdStorageEngine → BlockCache（LRU，off-heap 池化）
  hit  → 解码
  miss → MmapSSTableReader（MappedByteBuffer 零拷贝 + CRC）
FileChannelSSTableReader 保留为 baseline（benchmark 对比/降级）
```

- mmap 冷读零拷贝；MemoryPool（DirectByteBuffer 大小类池）管理缓存缓冲；
- IOStatistics 观测 readCount / cacheHit / cacheMiss / mappedBytes / 延迟。

## 生产基准（Phase 9）

- 三级基准：A 内存引擎（GET 4.7M / SET 4.4M ops/s）、B 服务端（pipeline64
  峰值 218–231K，目标 500K 未达——瓶颈在协议/调度层）、C 生产全链路
  （115–178K ops/s，P99 <5ms）；
- 容量模型与部署画像：docs/benchmark/capacity-model.md、
  deployment-profile.md；详见 docs/benchmark/phase9-* 报告。

## 生产化与优化（Phase 10）

- 响应批处理（自适应 batch=64 + 排空 flush）与回调式执行（对象削减）：
  Level B pipeline64×500 218–231K → 465K ops/s，pipeline128 → 1.14M；
- YAML 配置（config/application.yaml）、`INFO` 指标命令、优雅停机
  （drain + WAL force + checkpoint）。

## 分布式集群（Phase 11）

```text
Client → ClusterClient（slot 路由）→ MetadataServer（拓扑）
    → Shard Leader（ClusterNode）
        → Raft Group（Follower / Candidate / Leader）
            → ReplicatedStorageEngine
                → TieringStorageEngine（MemTable / WAL / SSTable）
```

- 哈希槽：CRC16/CCITT + 16384 slot（ADR-0035），与 Redis Cluster 语义一致，
  100K 键三 shard 分布 33.2% / 33.2% / 33.3%，路由开销仅 ~23ns/op；
- 元数据服务：JOIN / 拓扑查询 / leader 变更（ADR-0036）；
- 最小真实 Raft：随机化选举超时 + 心跳 + 日志复制（prevLog 校验 +
  nextIndex 回退）+ commit/apply（ADR-0037/0038），非简化假共识；
- 复制适配器：写经 Raft 日志复制后 apply 本地引擎，不改 MemTable/WAL/
  SSTable；读取走 leader 本地引擎；
- 基准（进程内原型，见
  [cluster-report.md](docs/benchmark/cluster-report.md)）：复制写 154K
  ops/s（P99=0.027ms）、读 750K ops/s（P99=4μs）、复制滞后 ≤35ms
  （心跳周期约束）、选举 124–310ms（目标 <5s ✅）、51 项新测试；
- 限制（如实声明）：Raft 消息进程内直调（无 TCP）、日志内存态（无磁盘
  持久化）、静态分片（无在线 slot 迁移），见 ROADMAP TD-022~025。

## 分布式生产化（Phase 12）

```text
RaftNode
  ├── RaftLog（分段文件 + CRC32C + SYNC/ASYNC/NONE + 尾部截断恢复）
  ├── RaftPersistentState（term / votedFor / commitIndex 落盘）
  ├── SnapshotManager（快照压缩 + InstallSnapshot 追赶）
  └── RaftTransport
        ├── LocalRaftTransport（测试/回退）
        └── NettyRaftTransport（TCP：连接复用 + RequestId + 超时重试）
```

- 持久化：重启后 term / 日志 / commitIndex 完整恢复（ADR-0039/0040）；
- 快照：日志超阈值自动压缩，重启 = 快照恢复 + 剩余日志重放；
- TCP RPC：AppendEntries / RequestVote / InstallSnapshot 二进制协议，
  连接复用、超时（3s）、幂等重试（ADR-0041）；
- 复制优化：CommitNotifier 提交后立即补发，滞后 13–35ms → **<1ms**
  （目标 <5ms ✅，ADR-0042）；
- 在线迁移：INIT→COPYING→VERIFYING→SWITCHING→DONE，checkpoint 续传 +
  CRC 校验 + 原子切换（ADR-0043）；
- 基准（[distributed-production-report.md](docs/benchmark/distributed-production-report.md)）：
  TCP 提交 P50=0.65ms / P99=2.16ms，RPC P50=100μs（单连接），
  迁移 16.1MB/s + 恢复 549ms/90K。

## 分布式优化（Phase 13）

- **批量/流水线复制**（ADR-0044）：batch AppendEntries（maxBatchEntries/
  maxBatchBytes/flushInterval）+ 多 in-flight + group commit，
  TCP 吞吐 700–1,359 → **9,220 ops/s**（64 并发写者）；
- **游标迁移**（ADR-0045）：单次扫描 + `slot-{start}.cursor`（CRC 保护）
  + PAUSED/恢复/崩溃续传，1KB 负载 **244.8MB/s**；
- **安全 RPC**（ADR-0046）：TLS（PEM 证书）+ Token 认证（含过期）+ 
  TokenBucket 限流（`ERR RATE_LIMIT`）；
- **元数据 Raft 化**（ADR-0047）：MetadataRaftGroup + MetadataClient，
  JOIN/拓扑/slot 归属/迁移状态走 Raft 日志，leader 故障转移 115ms；
- 基准：[phase13-report.md](docs/benchmark/phase13-report.md)；
  部署：[distributed-deployment.md](docs/deployment/distributed-deployment.md)。

## 生产加固（Phase 14）

- **批量写**（ADR-0048）：`MemTable.applyBatch`（单段单锁 + 版本预分配）
  + WAL 批量追加；
- **自适应 Flush/复制**（ADR-0049/0050）：AdaptiveFlushController +
  ReplicationController + `putAsync`（超时/取消/重试）；
- **安全升级**（ADR-0051）：HMAC-SHA256 签名 + nonce 防重放 + 双密钥
  轮换 + mTLS（ONE_WAY/MUTUAL）；
- **元数据持久化**（ADR-0052）：FileRaftLog + MetadataSnapshot，重启
  拓扑保留（194ms）；
- **故障注入**（5/5 通过）与跨机指南：
  [failure-injection.md](docs/testing/failure-injection.md) /
  [cross-machine-guide.md](docs/deployment/cross-machine-guide.md)；
- 基准：[phase14-production-report.md](docs/benchmark/phase14-production-report.md)
  （100B 迁移 18.3MB/s、Raft 37.3K ops/s，两个目标未达已如实记录）。

## 生产验证（Phase 15）

- **流式迁移**（ADR-0053）：单次快照扫描 + `MigrationStreamCursor` 游标
  （CRC + pause/resume/recover）+ 版本屏障 + 动态 batch；修复每批重建
  O(N) 快照的隐藏 O(N²) 行为，100B 迁移 2.9 → 59.8 MB/s；
- **全异步提案**（ADR-0054）：`RaftNode.proposeBatch`（N 请求 → 单次
  AppendEntries）+ `AsyncReplicationClient`（有界队列背压 + 内联批量
  drain + leader 变更重试）；1/64/256 写者 129/259/331K ops/s，
  P99 = 0.009/3.071/9.824ms；
- **证书生命周期**（ADR-0055）：CertificateManager（加载/校验/过期/
  原子轮换）+ CertificateWatcher（文件监听），轮换 p50=13.5ms，
  已有连接不中断；
- **混沌验证**（ADR-0053~0056 支撑）：16 项混沌测试（延迟/丢包/分区/
  磁盘慢/leader 击杀/混合故障/法定人数丢失），三轮稳定；发现并修复
  Raft 缺陷——冲突截断的未提交提案被新条目虚假完成；
- **可观测性**（ADR-0056）：ClusterMetricsRegistry（raft_proposal_qps /
  raft_commit_latency / raft_replication_lag / migration_speed /
  migration_cursor / migration_remaining / certificate_expire_time）+
  `INFO CLUSTER`（node/role/term/leader/slot）；
- 文档：[混沌报告](docs/testing/phase15-chaos-report.md)、
  [基准报告](docs/benchmark/phase15-production-validation-report.md)、
  [评审报告](docs/review/phase15-production-validation-review.md)。

## Multi-Raft 架构演进（Phase 16）

- **Region 抽象**（ADR-0057）：键范围 [startKey, endKey) + confVer/version
  纪元 + NORMAL/SPLITTING/MERGING/TOMBSTONE；RegionManager 路由/
  分裂/合并，旧纪元请求显式拒绝；
- **Multi-Raft**（ADR-0058）：MultiRaftNode + RaftGroupManager（每 Region
  独立 Raft 组）+ MultiRaftEndpoint（单端口组前缀路由，RaftNode API
  零改动）；吞吐随组数近似线性扩展（2 组 2.02×、4 组 3.68×）；
- **零拷贝批量写**（ADR-0059）：RawMutation 所有权转移 +
  MemTable.applyRawBatch（平面桶分组 + 单段单锁）+ SkipList 单次查找；
  100B 迁移 59.8 → 82.7 MB/s；
- **放置控制**（ADR-0060）：PlacementManager 分布/均衡检查/leader
  转移（epoch confVer 推进），自动 rebalance 暂缓；
- **混沌验证**：ChaosClusterTest 20 项（Region 级故障隔离），发现并
  修复 Raft 缺陷——新 leader 不回填滞后副本（心跳不匹配回退 nextIndex）；
- **可观测性**：RegionMetricsRegistry + `INFO REGIONS`
  （region/leader/epoch/size/state）；
- **跨机部署**：[Docker Compose + netem 混沌](docs/deployment/phase16-cross-machine.md)
  （ClusterMain 三节点入口）；
- 基准：[phase16-multiraft-report.md](docs/benchmark/phase16-multiraft-report.md)；
  评审：[phase16-multiraft-review.md](docs/review/phase16-multiraft-review.md)。

## Region 生命周期（Phase 17）

- **Region Split**（ADR-0061）：NORMAL→SPLITTING→SPLIT_READY→NORMAL +
  PREPARE/SNAPSHOT/INSTALL/COMMIT/CLEANUP 五阶段；分裂窗口写缓冲，
  10000 并发写无丢失；1M 键（外推）<1s；
- **Region Merge**（ADR-0062）：PREPARE→LOCK→TRANSFER→UPDATE_META→
  TOMBSTONE；右→左零拷贝搬迁，故障后状态重置可重试；1M 键（外推）
  <1s；
- **并行迁移**（ADR-0063）：按段分片 + chunk 检查点 + 8 worker，
  100B 209.1 MB/s（>150 ✅）、1KB 986、10KB 1952 MB/s；
- **真实 Leader Transfer**（ADR-0064）：TimeoutNow 立即选举 + 日志追平
  校验，24ms（<500ms ✅）；200ms 延迟 + 10% 丢包下仍成功；
- **Redis Cluster Gateway**：GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS，
  非本地键返回 `MOVED slot host:port`；GET 3.68M / SET 1.67M ops/s；
- **自动均衡**（ADR-0065）：BalanceScheduler 检测 region/leader/disk/cpu
  压力并生成 BalancePlan（epoch 保护，不自动执行危险迁移）；
- **可观测性**：INFO RAFT / INFO MIGRATION（leader_transfer_total /
  election_total / proposal_latency / migration_bytes / migration_speed /
  region_merge_count）；
- 文档：[基准报告](docs/benchmark/phase17-region-report.md)、
  [评审报告](docs/review/phase17-region-lifecycle-review.md)。

## 分布式生产集成（Phase 18）

- **统一路由**（ADR-0066）：RoutingTable（键范围 + slot 区间 + epoch +
  leader + raftGroup）+ RoutingCache（陈旧自刷新）+ RouteEpochGuard；
  MOVED/ASK/TRYAGAIN 语义统一；
- **真实 TCP 网关**（ADR-0068）：NettyClusterGateway（EventLoop →
  RESP → CommandDispatcher → UnifiedRouter），pipeline 批量 flush；
  GET 719K / SET 590K ops/s（>500K/200K ✅）；
- **Split/Merge 与 Raft 联动**（ADR-0067）：RegionRaftMigrationManager
  （子/合并组创建 + 路由原子切换 + 失败回滚 + 恢复幂等）；
- **生产化迁移**：ByteRateLimiter（限速）+ MigrationScheduler（IO 压力/
  backlog 自适应）+ migration_remaining/error 指标；100B 209MB/s；
- **跨节点部署**（ADR-0069）：[docker-compose.cluster.yml](deploy/docker-compose.cluster.yml)
  + CrossMachineChaosTest（20 项：击杀/分区/恢复/快照追赶/迁移中断）；
- **可观测性**（ADR-0070）：MetricsExporter（Prometheus 格式）+
  ProductionInfo（INFO CLUSTER 聚合 Region/Raft/Migration/Gateway）；
- 文档：[基准报告](docs/benchmark/phase18-production-report.md)、
  [评审报告](docs/review/phase18-production-integration-review.md)。

## MVCC 与事务引擎（Phase 19）

- **MVCC**（ADR-0071）：底层键 `[userKey][type][startTS][commitTS]` +
  MvccStorageEngine adapter + 内存版本索引（启动重建）；
- **时间戳**（ADR-0072）：TimestampOracle（原子单调/批量/恢复不回退）+
  HybridLogicalClock（回拨安全）；
- **事务**（ADR-0073）：Percolator 2PC（BEGIN→Prewrite→Commit/Rollback）
  + TransactionCoordinator 跨 Region 2PC（参与者键归属，无部分提交）；
- **锁与冲突**（ADR-0074）：LockTable（TTL 防永久锁）+ 写写/读写/锁冲突；
- **恢复**（ADR-0076）：超时回滚 / primary 补完 / 无永久锁；
- **GC**（ADR-0075）：SafePoint + 保留最新版本（19–29MB/s，未达 100，
  如实登记 TD-041）；
- **基准**：MVCC GET 3.1–4.7M ops/s、单区事务 70.8–204.6K txn/s、
  冲突检测 2.1–7.6M ops/s；
- 文档：[MVCC](docs/architecture/mvcc.md) / [事务](docs/architecture/transaction.md) /
  [一致性](docs/architecture/consistency.md) /
  [基准](docs/benchmark/phase19-mvcc-report.md) /
  [评审](docs/review/phase19-mvcc-transaction-review.md)。

## 事务生产化与存储优化（Phase 20）

- **批量 GC**（ADR-0078）：`mvcc/gc` BatchGcExecutor（索引规划 + 分段
  批量物理删除 + 并行 worker），107–285MB/s（>100 ✅，TD-041 关闭）；
- **网关自动事务**（ADR-0079）：GET=快照读、SET/DEL=单键事务、
  MGET=一致快照、MSET=跨 shard 2PC；RESP 不变（TD-042 关闭）；
- **持久化 MVCC 索引**（ADR-0080）：Writer/Reader/Snapshot + 增量重建；
- **事务日志 Raft 持久化**（ADR-0081）：COMMIT 决策先落盘 + 恢复重放
  （无幻影提交 / 无丢失提交）；
- **可观测性**：INFO TRANSACTION / INFO MVCC、Prometheus
  （txn_abort/recovery、mvcc_versions/gc_deleted、redis_txn_latency）；
- **基准**：网关 GET 2.0–6.9M、SET 141–389K ops/s、单区事务
  324–651K txn/s、跨区 62–158K txn/s、恢复 1–4ms，全部达标；
- 文档：[基准](docs/benchmark/phase20-report.md) /
  [评审](docs/review/phase20-transaction-production-review.md) /
  [混沌](docs/testing/phase20-chaos-report.md)。

## 分布式事务网络化与云生产（Phase 21）

- **分布式事务路由**（ADR-0083）：DistributedTxnRouter + RegionTxnClient +
  TxnParticipantClient（PREWRITE/COMMIT/ROLLBACK/HEARTBEAT 复用
  MultiRaftEndpoint 单端口 RPC），participant 状态机幂等；
- **事务元数据 Raft**（ADR-0084）：TransactionMetadataService +
  TxnMetadataRaftGroup，Coordinator 崩溃可恢复续跑；
- **MVCC 在线压缩**（ADR-0085）：MvccCompactor（SafePoint 合并 + 原子索引文件）；
- **真实跨机混沌**（ADR-0086）：Docker 三节点 + tc netem（100ms/5%/2%）
  + 分区 + kill -9，全部存活恢复；
- **可观测性**：txn_prepare/network_retry/lock_wait/region_count/recovery_time +
  mvcc_compaction_*；
- **基准**：单区事务 58.7–116.4K txn/s、多区 88.1–110.7K、恢复 0–0ms、
  leader 恢复 156–276ms；
- 文档：[基准](docs/benchmark/phase21-report.md) /
  [评审](docs/review/phase21-distributed-transaction-review.md) /
  [混沌](docs/testing/phase21-real-chaos-report.md)。

## 事务可靠性与生产运行时（Phase 22）

- **决策排序**（ADR-0087）：decisionIndex + Raft-first，消除本地日志先行
  窗口；恢复覆盖元数据 COMMITTED 后 participant 未提交的崩溃窗口；
- **生命周期**（ADR-0088）：TTL / max-duration / 心跳续约 / 超时自动
  abort（txn.ttl-seconds / max-duration-seconds）；
- **锁解析**（ADR-0089）：LockResolver + TxnStatusCache
  （orphan / primary / secondary）；
- **运行时**（ADR-0090）：TCP 端到端事务链路 + participant 重启恢复；
- **指标**：txn_expired_total / long_running / abort_reason /
  lock_total / lock_resolve_total / lock_wait_seconds；
- **基准**：SET 128–150K、GET 3.9–25M、跨区 33.6–59.7K txn/s、
  恢复 0–15ms、锁解析 50–129ms；
- 文档：[基准](docs/benchmark/phase22-report.md) /
  [评审](docs/review/phase22-transaction-reliability-review.md) /
  [混沌](docs/testing/phase22-chaos-report.md) /
  [部署](docs/deployment/phase22-runtime-deployment.md)。

## 事务运行时最终化（Phase 23）

- **运行时**（ADR-0093）：gateway/coordinator/participant/metadata 独立
  JVM 角色 + docker-compose.transaction.yml，全链路 TCP；
- **生命周期持久化**（ADR-0091）：TxnLifecycleRecord + MetadataRaft，
  重启恢复/心跳/过期 abort；
- **LockResolver RPC**（ADR-0092）：CHECK_TXN_STATUS / RESOLVE_LOCK /
  HEARTBEAT_LOCK 跨 Region 解析；
- **磁盘混沌**（ADR-0094）：disk full/readonly/slow 语义验证 + 容器式
  重启恢复，零提交丢失；
- **测试**：新增 158 项，全量 **2007/2007**；
- 文档：[基准](docs/benchmark/phase23-runtime-report.md) /
  [评审](docs/review/phase23-runtime-finalization-review.md) /
  [混沌](docs/testing/phase23-chaos-report.md) /
  [生产配置](docs/deployment/production-runtime.md)。

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 | Java 17（LTS，`maven.compiler.release=17`） |
| 构建 | Maven 3.9+（pom.xml） |
| 测试 | JUnit 5（单元） + 集成测试（tests/） + JMH 压力测试（benchmarks/） |
| 网络 | Netty 4.1 事件循环模型（已引入，ADR-0003 / ADR-0006） |

## 目录结构

```text
tiering-kv/
├── .codex/                              # AI Agent 工程控制中心
│   ├── MASTER_PROMPT.md                 # Agent 最高规则
│   ├── DEVELOPMENT_RULES.md             # 开发规范
│   ├── AGENT_CONTEXT.md                 # 当前项目状态
│   ├── CODE_REVIEW_RULES.md             # AI 代码审查规则
│   ├── RELEASE_RULES.md                 # 发布流程
│   └── tasks/                           # 阶段任务文件
│       ├── phase0-init.md
│       ├── phase1-protocol.md
│       ├── phase2-storage.md
│       ├── phase3-cache.md
│       └── phase4-benchmark.md
│
├── docs/
│   ├── requirements/                    # 需求（requirements + acceptance）
│   ├── architecture/                    # 架构设计（overview / storage / network / concurrency）
│   ├── adr/                             # 架构决策记录（ADR-0001 ~ 0038）
│   ├── design/                          # 详细设计（protocol / memory / lsm / bitcask / eviction）
│   ├── benchmark/                       # 性能报告（计划 + 报告占位）
│   ├── review/                          # 技术评审
│   └── operations/                      # 运维文档
│
├── src/
│   ├── main/                            # 模块骨架：network / protocol / command / storage / cache / scheduler / memorypool / metrics / config
│   └── test/
│
├── tests/                               # 自动化测试（unit / integration / stress / chaos）
│
├── benchmarks/                          # 性能测试（throughput / latency / memory / migration）
│
├── scripts/                             # 工程脚本（build / benchmark / stress-test / release）
│
├── config/                              # 配置（tiering-kv.yaml / benchmark.yaml）
│
├── examples/                            # 使用示例
│
├── tools/                               # 开发工具（profiler / analyzer）
│
├── .github/workflows/                   # CI/CD（build / test / benchmark）
│
├── README.md
├── ROADMAP.md
├── CHANGELOG.md
├── CONTRIBUTING.md
├── LICENSE
└── .gitignore
```

> `pom.xml`（Maven 构建）按最初目录规范保留；`src/main/<module>` 为框架骨架目录，
> Java 代码落地时映射到 `src/main/java/io/tieringkv/<module>/`（TD-004）。

## Codex 工程控制文件

- [MASTER_PROMPT.md](.codex/MASTER_PROMPT.md)：主控提示词，定义角色、目标与流程。
- [DEVELOPMENT_RULES.md](.codex/DEVELOPMENT_RULES.md)：开发规范（ADR / Git / TDD / 安全机制）。
- [AGENT_CONTEXT.md](.codex/AGENT_CONTEXT.md)：项目长期上下文，每次会话先读取。
- [CODE_REVIEW_RULES.md](.codex/CODE_REVIEW_RULES.md)：代码审查规则与门禁。
- [RELEASE_RULES.md](.codex/RELEASE_RULES.md)：发布流程（SemVer + tag + 回归门禁）。
- [tasks/](.codex/tasks/)：阶段任务文件（phase0–phase4）。

## 开发流程

每个阶段严格遵循：

```text
需求 → 设计 → ADR → 实现（TDD） → 测试 → 性能验证 → Git Commit
```

Git 分支策略：

```text
main（稳定）
 └── develop（集成）
      ├── feature/protocol
      ├── feature/storage-engine
      ├── feature/cache-policy
      ├── feature/io-optimization
      └── feature/benchmark
```

Commit 采用 Conventional Commit（feat / fix / refactor / test / perf / docs /
build / chore），每个阶段至少一次语义化提交。

## 文档

- 需求：[requirements.md](docs/requirements/requirements.md) /
  [acceptance.md](docs/requirements/acceptance.md)
- 架构：[overview.md](docs/architecture/overview.md) 与
  [storage](docs/architecture/storage-architecture.md) /
  [network](docs/architecture/network-architecture.md) /
  [concurrency](docs/architecture/concurrency-model.md)
- 设计：[docs/design/](docs/design/)（protocol / memory / lsm / bitcask / eviction）
- Benchmark：[benchmark-plan.md](docs/benchmark/benchmark-plan.md)，报告 Phase 9 填充
- 评审：[docs/review/](docs/review/)；运维：[docs/operations/](docs/operations/)
- 路线图：[ROADMAP.md](ROADMAP.md)
- 变更记录：[CHANGELOG.md](CHANGELOG.md)
- ADR 索引：[docs/adr/](docs/adr/)（0001–0005）
- 贡献：[CONTRIBUTING.md](CONTRIBUTING.md)；License：[LICENSE](LICENSE)

## 性能目标

| 指标 | 目标 |
| --- | --- |
| 热点 GET P50 / P95 / P99 | < 0.5ms |
| 并发连接 | 1k / 10k / 100k |
| 内存占用（对比纯内存 Redis） | 降低 60%–80% |

## 快速开始

```bash
mvn test                  # 单元 + 集成 + 基准 + 混沌（Phase 1–15，650 个用例）
mvn -q exec:java          # 启动服务，默认 0.0.0.0:6379
redis-cli -p 6379         # PING / ECHO / SET / GET / DEL / EXISTS
```

当前支持命令：PING / ECHO / SET / GET / DEL / EXISTS（Phase 1，RESP2）。
