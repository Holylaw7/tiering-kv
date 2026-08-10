# AGENT_CONTEXT — 项目长期上下文

> 每次会话开始时阅读本文档与仓库状态，快速恢复上下文。

## 1. 项目概况

Tiering-KV：Mini Redis 冷热分层存储引擎。核心能力：
RESP 协议兼容、内存 + 磁盘冷热分层、LFU/ARC 热度管理、异步冷热迁移、
Bitcask/LSM 持久化、高并发网络、mmap 零拷贝、分段锁/无锁、
Bloom Filter、自研 Memory Pool。

当前定位：RESP 兼容 KV Server 基础层（Phase 1 已交付协议/命令/网络层）；
分层存储与性能优化为演进目标（ROADMAP Phase 2–10）。

Phase 1 已交付命令：PING / ECHO / SET / GET / DEL / EXISTS（RESP2）。

Phase 2 已交付：StorageEngine SPI、64 段 SkipList MemTable、分段读写锁、
TTL（惰性 + 主动）、MemoryManager（配额 + 淘汰回调接口）、有序迭代器。

## 2. 当前状态

- 阶段：**Phase 2（内存 KV 核心）✅ 已完成**（Phase 0/1 ✅）；
- 最近提交：`feat(storage): implement memory tier engine`（详见 git log）；
- 基线：tag `phase-0`；分支策略：feature/* 合并入 develop，main 保持稳定；
- 下一步：**Phase 3 缓存淘汰（LFU / ARC）**（等待用户指令）。

## 3. 技术栈

| 项 | 选型 |
| --- | --- |
| 语言 | Java 17（LTS，`maven.compiler.release=17`） |
| 构建 | Maven 3.9+（pom.xml，单模块起步） |
| 测试 | JUnit 5（单元）；tests/（集成）；benchmarks/（JMH 压测） |
| 网络 | Netty 4.1.115 事件循环（已引入，ADR-0006） |
| 内存层 | MemTable（64 段 SkipList + 分段读写锁，ADR-0007/0008/0009） |
| 包结构 | `io.tieringkv.{network,protocol,command,storage,memory,cache,eviction,wal,sstable,compaction,scheduler,metrics,benchmark}` |

## 4. 关键决策（ADR）

| ADR | 决策要点 |
| --- | --- |
| [ADR-0001](adr/ADR-0001-project-architecture.md) | Java 17 + Maven 单模块；分层单向依赖；main/develop/feature 分支 |
| [ADR-0002](adr/ADR-0002-storage-engine.md) | StorageEngine SPI；Bitcask 先行（Phase 4）、LSM-Tree 演进（Phase 5）；WAL 独立 |
| [ADR-0003](adr/ADR-0003-concurrency-model.md) | Netty 事件循环 + key 分片执行 + 分段锁 + 异步迁移；禁止全局锁 |
| [ADR-0004](adr/ADR-0004-cache-policy.md) | LFU + ARC 混合热度管理 + Bloom Filter 防击穿 |
| [ADR-0005](adr/ADR-0005-persistence-format.md) | 自定义二进制持久化格式（版本化记录 + CRC32C） |
| [ADR-0006](adr/ADR-0006-resp-protocol.md) | RESP2 线上协议；inline 兼容；Phase 1 连接内同步执行 |
| [ADR-0007](adr/ADR-0007-memtable-data-structure.md) | MemTable 采用 SkipList（有序 + 迭代 + LSM 衔接） |
| [ADR-0008](adr/ADR-0008-memory-concurrency-model.md) | 64 段 Striped Lock（读写锁），无全局锁 |
| [ADR-0009](adr/ADR-0009-ttl-management-strategy.md) | TTL 惰性 + 主动混合（min-heap + 版本守卫） |

## 5. 仓库布局

```text
tiering-kv/
├── .codex/          # 工程控制中心（规则 + tasks/）
├── docs/
│   ├── requirements/  # requirements.md + acceptance.md
│   ├── architecture/  # overview + storage/network/concurrency
│   ├── adr/           # ADR-0001 ~ 0005
│   ├── design/        # protocol/memory/lsm/bitcask/eviction
│   ├── benchmark/     # 计划 + 报告占位
│   ├── review/        # 评审记录
│   └── operations/    # 部署/配置/故障
├── src/main/          # 模块骨架（network/protocol/command/storage/cache/…）
├── src/test/  tests/{unit,integration,stress,chaos}/
├── benchmarks/{throughput,latency,memory,migration}/
├── scripts/  config/  examples/  tools/  .github/workflows/
├── pom.xml            # Maven 构建（框架树未列出，保留）
├── README.md  ROADMAP.md  CHANGELOG.md  CONTRIBUTING.md  LICENSE  .gitignore
```

> 注：`src/main/<module>` 为框架骨架目录；Phase 1 落地 Java 代码时映射到 Maven
> 标准布局 `src/main/java/io/tieringkv/<module>/`（见 TD-004）。

## 6. Roadmap 状态

| Phase | 目标 | 状态 |
| --- | --- | --- |
| 0 | 工程初始化 | ✅ |
| 1 | RESP 协议 | ✅ |
| 2 | 内存 KV 核心 | ✅ |
| 3 | LFU / ARC | 未开始 |
| 4 | Bitcask | 未开始 |
| 5 | LSM Tree | 未开始 |
| 6 | 冷热迁移 | 未开始 |
| 7 | 并发优化 | 未开始 |
| 8 | mmap / Memory Pool | 未开始 |
| 9 | Benchmark | 未开始 |
| 10 | 生产化 | 未开始 |

## 7. 技术债

| 编号 | 描述 | 计划消除 |
| --- | --- | --- |
| TD-001 | 单 Maven 模块；模块耦合升高时评估拆分多模块 | Phase 7 前评估 |
| TD-002 | JDK 17 目标暂不采用虚拟线程 | Phase 7 评估 JDK 21 |
| TD-003 | 尚未引入架构约束测试（ArchUnit） | Phase 1 评估 |
| TD-004 | src/main 框架骨架目录与 Maven src/main/java 布局的映射 | ✅ 已关闭（Phase 1） |

## 8. 会话启动清单

1. `git status` + `git log --oneline -10`；
2. 阅读 README.md、ROADMAP.md、CHANGELOG.md；
3. 阅读 .codex/DEVELOPMENT_RULES.md、.codex/CODE_REVIEW_RULES.md、
   .codex/RELEASE_RULES.md；
4. 阅读 docs/adr/ 目录与 .codex/tasks/ 对应任务文件；
5. 对照 ROADMAP 与本文档确认当前阶段、未完成任务与技术债。
