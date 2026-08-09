# Tiering-KV 需求文档（Requirements）

版本：0.1（Phase 0 基线）

状态：草稿，随 Phase 1 需求分析持续细化

## 1. 项目目标

从零自主实现一个兼容 Redis 协议的高性能冷热分层 KV 存储系统（Mini Redis），
在保持协议兼容的前提下，将纯内存方案的内存占用降低 60%–80%。

## 2. 功能需求

| 编号 | 需求 | 说明 | 关联阶段 |
| --- | --- | --- | --- |
| FR-01 | RESP 协议兼容 | 支持 Redis 请求/响应协议编解码与错误语义 | Phase 1 |
| FR-02 | 冷热分层存储 | 热数据驻留内存（MemTable），冷数据落盘 | Phase 2/6 |
| FR-03 | 热度管理 | LFU / ARC 访问计数、衰减与冷热判定 | Phase 3 |
| FR-04 | 异步冷热迁移 | 升降级迁移异步执行，不阻塞主读写 | Phase 6 |
| FR-05 | 持久化 | Bitcask（Phase 4）与 LSM-Tree（Phase 5）双引擎演进 + WAL | Phase 4/5 |
| FR-06 | 高并发网络 | 支撑 1k / 10k / 100k 并发连接 | Phase 1/7 |
| FR-07 | mmap 零拷贝 | 冷存储读写减少用户态拷贝 | Phase 8 |
| FR-08 | 分段锁 / 无锁 | 分片并发控制，避免全局锁 | Phase 7 |
| FR-09 | Bloom Filter | 降低不存在键的读放大，防缓存击穿 | Phase 5 |
| FR-10 | 自研 Memory Pool | 复用缓冲与对象，降低 GC 压力 | Phase 8 |

## 3. 非功能需求

| 编号 | 需求 | 目标 |
| --- | --- | --- |
| NFR-01 | 延迟 | 热点 GET P50 < 0.5ms；P95/P99 在 Phase 9 建立基线 |
| NFR-02 | 并发 | 1k / 10k / 100k 连接压测 |
| NFR-03 | 内存 | 对比纯内存 Redis 降低 60%–80% |
| NFR-04 | 一致性 | WAL 回放崩溃恢复；迁移期间读旧写新的版本控制 |
| NFR-05 | 可观测性 | metrics 模块提供延迟、队列、竞争、放大系数等指标 |
| NFR-06 | 可测试性 | 核心模块具备单元、集成、压力三层测试 |

## 4. 约束

- 从零自研，不直接绑定 RocksDB 等第三方存储引擎；
- Java 17（LTS）+ Maven；接口优先、SOLID；
- 所有阶段强制走 ADR → TDD → 性能验证 → Conventional Commit 流程。

## 5. 范围外（当前阶段）

- 集群与分片、持久订阅、Lua 脚本、RESP3（Phase 1 评估）。

## 6. 待细化项（Phase 1 处理）

- 支持的 Redis 命令集合（SET / GET / DEL / PING / ECHO / EXISTS / TTL…）；
- RESP2 vs RESP3 选择；
- WAL fsync 一致性级别；
- 冷热迁移触发阈值与采样窗口。
