# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
- ADR-0006（RESP2）；依赖：Netty 4.1.115、AssertJ 3.26.3、exec 插件。

### Changed

- 目录结构与标准框架对齐；ADR-0002 更名为 ADR-0002-storage-engine.md；
  architecture.md 拆分为 overview + 三个分主题架构文档。
- src/main 骨架目录统一为 Maven 标准布局 `src/main/java/io/tieringkv`
  （TD-004 关闭）。
- 修复：Netty 管道顺序（Encoder 位于 pipeline 最前，符合出站事件方向）；
  ByteProcessor 扫描语义修正。

## [0.1.0] - 2026-08-09

### Added

- 初始化 Git 仓库（main + develop 分支）与完整目录骨架。
- 新增 README.md、ROADMAP.md、CHANGELOG.md、.gitignore。
- 新增 Maven 构建骨架（Java 17、JUnit 5），`mvn test` 可验证。
- 新增 ADR-0001（项目总体架构）、ADR-0002（存储引擎策略）、ADR-0003（并发模型）。
