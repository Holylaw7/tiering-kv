# Tiering-KV

> RESP 兼容 KV Server 基础层（演进目标：Mini Redis 冷热分层存储引擎）。

**阶段状态：Phase 1（RESP 协议）✅（Phase 0 工程初始化 ✅）**

## 项目定位

**当前定位**：RESP 兼容的 KV Server 基础层——协议、命令与网络层已可用（Phase 1），
面向 redis-cli 与主流客户端提供基础 PING / ECHO / SET / GET / DEL / EXISTS 能力。

**演进目标（非当前承诺）**：内存热数据 + 磁盘冷数据的分层存储，在保持 Redis 协议
兼容的同时降低内存占用 60%–80%；分层存储、持久化、迁移与性能优化按
ROADMAP Phase 2–10 逐步落地。

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

- 写路径：WAL append（默认 EVERY_SEC，≤1s 丢失窗口）→ MemTable → ack；
- 记录格式：MAGIC / VERSION / TYPE / 时间戳 / 长度 / TTL / 版本 + CRC32C
  （ADR-0015，禁用 Java 序列化）；
- segment 滚动（`wal/%06d.log`，64MB）+ checkpoint（快照 + offset）加速恢复；
- 恢复时按绝对过期点判定 TTL，宕机期间过期的键不复活。

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
│   ├── adr/                             # 架构决策记录（ADR-0001 ~ 0005）
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
mvn test                  # 单元 + 集成 + 延迟冒烟测试（47 个用例）
mvn -q exec:java          # 启动服务，默认 0.0.0.0:6379
redis-cli -p 6379         # PING / ECHO / SET / GET / DEL / EXISTS
```

当前支持命令：PING / ECHO / SET / GET / DEL / EXISTS（Phase 1，RESP2）。
