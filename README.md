# Tiering-KV

> Mini Redis 冷热分层存储引擎 —— 兼容 Redis RESP 协议的高性能键值存储系统。

**阶段状态：Phase 0（工程初始化）✅**

## 项目定位

从零自主实现一个生产级 Mini Redis：内存热数据 + 磁盘冷数据的分层存储，在保持
Redis 协议兼容的同时，将纯内存方案的内存占用降低 60%–80%。

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

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 | Java 17（LTS，`maven.compiler.release=17`） |
| 构建 | Maven 3.9+（pom.xml） |
| 测试 | JUnit 5（单元） + 集成测试（tests/） + JMH 压力测试（benchmarks/） |
| 网络 | Netty 事件循环模型（Phase 1 引入，见 ADR-0003） |

## 目录结构

```text
tiering-kv/
├── .codex/
│   ├── MASTER_PROMPT.md       # Codex 主控提示词（核心）
│   ├── DEVELOPMENT_RULES.md   # 开发规范
│   └── AGENT_CONTEXT.md       # 项目长期上下文
├── docs/
│   ├── requirements/          # 需求文档
│   ├── architecture/          # 架构文档
│   ├── adr/                   # 架构决策记录（ADR）
│   ├── design/                # 详细设计
│   ├── benchmark/             # 性能测试报告
│   └── review/                # 评审记录
├── src/                       # 主代码（src/main/java）
├── tests/                     # 集成测试
├── benchmarks/                # 压力 / 性能测试
├── scripts/                   # 构建、部署脚本（原规范保留）
├── config/                    # 运行时配置（原规范保留）
├── pom.xml                    # Maven 构建（原规范保留）
├── README.md
├── ROADMAP.md
└── CHANGELOG.md
```

## Codex 工程控制文件

- [MASTER_PROMPT.md](.codex/MASTER_PROMPT.md)：主控提示词，定义角色、目标与流程。
- [DEVELOPMENT_RULES.md](.codex/DEVELOPMENT_RULES.md)：开发规范（ADR / Git / TDD / 安全机制）。
- [AGENT_CONTEXT.md](.codex/AGENT_CONTEXT.md)：项目长期上下文，每次会话先读取。

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

- 需求：[requirements.md](docs/requirements/requirements.md)
- 架构：[architecture.md](docs/architecture/architecture.md)
- 路线图：[ROADMAP.md](ROADMAP.md)
- 变更记录：[CHANGELOG.md](CHANGELOG.md)
- ADR 索引：[docs/adr/](docs/adr/)

## 性能目标

| 指标 | 目标 |
| --- | --- |
| 热点 GET P50 / P95 / P99 | < 0.5ms |
| 并发连接 | 1k / 10k / 100k |
| 内存占用（对比纯内存 Redis） | 降低 60%–80% |

## 快速开始

当前为 Phase 0 骨架，尚无可用服务端；可执行构建验证：

```bash
mvn test
```
