# ADR-0001: Project Architecture

## Status

Accepted

## Context

Tiering-KV 是一个从零实现的、兼容 Redis 协议的冷热分层 KV 存储引擎，要求：

- 支持 1k / 10k / 100k 并发连接；
- 热点 GET P50 < 0.5ms；
- 相比纯内存 Redis 降低 60%–80% 内存占用；
- 具备 WAL、Bitcask/LSM 持久化、异步冷热迁移、Bloom Filter、Memory Pool 等能力。

Phase 0 必须确定技术栈、仓库结构与模块边界，作为后续所有阶段的基础。
环境约束：开发机为 Windows，已安装 JDK 17 与 Maven 3.9.16，未安装 Gradle。

## Decision

1. **语言与运行时**：Java 17（LTS），`maven.compiler.release=17`，可在 JDK 17+ 上运行。
2. **构建工具**：Maven（pom.xml），单模块起步；当包级边界无法控制耦合时，再评估
   Maven 多模块拆分（需新 ADR）。
3. **代码组织**：`io.tieringkv` 根包下的模块分包：`network`、`protocol`、`command`、
   `storage`、`memory`、`cache`、`eviction`、`wal`、`sstable`、`compaction`、
   `scheduler`、`metrics`、`benchmark`。
4. **分层架构**（依赖自上而下单向）：

   ```text
   Client → RESP Protocol → Network Layer → Command Engine
   → Memory Tier (MemTable) → Hotness Manager → Cold Storage
   → Bitcask / LSM Tree
   ```

   横切模块（WAL、Scheduler、Metrics、Eviction、Compaction、Memory Pool）以接口
   注入，不参与主调用链的循环依赖。
5. **接口优先**：跨层只允许依赖接口；核心数据结构（MemTable、SSTable、索引）不
   直接暴露给上层；禁止反向依赖与跨层穿透。
6. **Git 分支策略**：`main`（稳定）为唯一长期稳定分支，`develop` 为集成分支，
   `feature/*` 按模块创建；每个 Phase 至少一次 Conventional Commit。
7. **测试分层**：单元测试（`src/test/java`）、集成测试（`tests/`）、压力/性能测试
   （`benchmarks/` + JMH）。

## Alternatives

1. **C++**：极致性能与内存控制，但开发周期长、内存安全风险高，不利于快速迭代。
2. **Rust**：内存安全且性能优秀，但团队学习成本与迭代速度劣势明显。
3. **Go**：并发模型好，但对 mmap、无锁结构与精细内存池控制较弱。
4. **Gradle**：构建灵活，但本机未安装，且单模块 Maven 已满足当前需求。

## Consequences

**优点：**

- Java 生态成熟（Netty、JMH、JFR），工具链完整；
- 单构建文件使 Phase 0–3 迭代成本最低；
- 包级模块边界兼顾可测试性与开发速度。

**缺点：**

- JVM 内存管理带来 GC 开销，需在 Phase 8/9 通过 off-heap、Memory Pool 与
  Benchmark 验证；
- 单模块下包边界依赖纪律依赖 Code Review 与架构测试（后续引入 ArchUnit）。

**风险：**

- 模块间出现隐式耦合 → 依赖方向检查 + 架构测试兜底；
- 性能不达标 → 通过 metrics 持续观测，性能优化阶段集中处理。

## Implementation

- 仓库结构：`tiering-kv/`（docs、src、tests、benchmarks、scripts、config、
  pom.xml、README、ROADMAP、CHANGELOG、.gitignore）。
- 构建：`pom.xml`（Java 17、UTF-8、JUnit 5）。
- 本 ADR 约束 Phase 1–10 的模块创建与依赖引入。
