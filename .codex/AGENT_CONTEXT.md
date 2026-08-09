# AGENT_CONTEXT — 项目长期上下文

> 每次会话开始时阅读本文档与仓库状态，快速恢复上下文。

## 1. 项目概况

Tiering-KV：Mini Redis 冷热分层存储引擎。核心能力：
RESP 协议兼容、内存 + 磁盘冷热分层、LFU/ARC 热度管理、异步冷热迁移、
Bitcask/LSM 持久化、高并发网络、mmap 零拷贝、分段锁/无锁、
Bloom Filter、自研 Memory Pool。

## 2. 当前状态

- 阶段：**Phase 0（工程初始化）✅ 已完成**；
- 最近提交：`3f69a98 chore(init): bootstrap Tiering-KV project skeleton (Phase 0)`；
- 分支：`main` = `develop` = `3f69a98`；tag：`phase-0`；
- 工作树：干净；
- 下一步：**Phase 1 RESP 协议**（等待用户指令）。

## 3. 技术栈

| 项 | 选型 |
| --- | --- |
| 语言 | Java 17（LTS，`maven.compiler.release=17`） |
| 构建 | Maven 3.9+（pom.xml，单模块起步） |
| 测试 | JUnit 5（单元）；tests/（集成）；benchmarks/（JMH 压测） |
| 网络 | Netty 事件循环（Phase 1 引入） |
| 包结构 | `io.tieringkv.{network,protocol,command,storage,memory,cache,eviction,wal,sstable,compaction,scheduler,metrics,benchmark}` |

## 4. 关键决策（ADR）

| ADR | 决策要点 |
| --- | --- |
| [ADR-0001](adr/ADR-0001-project-architecture.md) | Java 17 + Maven 单模块；分层单向依赖；main/develop/feature 分支 |
| [ADR-0002](adr/ADR-0002-storage-strategy.md) | StorageEngine SPI；Bitcask 先行（Phase 4）、LSM-Tree 演进（Phase 5）；WAL 独立 |
| [ADR-0003](adr/ADR-0003-concurrency-model.md) | Netty 事件循环 + key 分片执行 + 分段锁 + 异步迁移；禁止全局锁 |

## 5. 仓库布局

```text
tiering-kv/
├── .codex/          # MASTER_PROMPT / DEVELOPMENT_RULES / AGENT_CONTEXT
├── docs/
│   ├── requirements/  # requirements.md
│   ├── architecture/  # architecture.md
│   ├── adr/           # ADR-0001 ~ 0003
│   ├── design/        # 详细设计（Phase 2 起）
│   ├── benchmark/     # 性能报告（Phase 9）
│   └── review/        # 评审记录
├── src/  tests/  benchmarks/
├── scripts/  config/  pom.xml   # 原规范保留的构建/运维设施
├── README.md  ROADMAP.md  CHANGELOG.md  .gitignore
```

## 6. Roadmap 状态

| Phase | 目标 | 状态 |
| --- | --- | --- |
| 0 | 工程初始化 | ✅ |
| 1 | RESP 协议 | 未开始 |
| 2 | 内存 KV 核心 | 未开始 |
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

## 8. 会话启动清单

1. `git status` + `git log --oneline -10`；
2. 阅读 README.md、ROADMAP.md、CHANGELOG.md；
3. 阅读 docs/adr/ 目录；
4. 对照 ROADMAP 与本文档确认当前阶段、未完成任务与技术债。
