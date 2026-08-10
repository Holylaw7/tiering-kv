# Tiering-KV 验收标准（Acceptance）

> 与 requirements.md 的 FR / NFR 一一对应；验证方式分为自动化测试、压测与演示。

| 编号 | 验收标准 | 验证方式 | 阶段 |
| --- | --- | --- | --- |
| FR-01 | redis-cli / redis-benchmark 可完成 SET/GET/DEL/PING/ECHO，错误响应符合 RESP 语义 | 集成测试 | Phase 1 ✅ 已完成 |
| FR-02 | 热数据命中内存、冷数据落盘，读路径自动升热 | 集成测试 + 压测 | Phase 6 |
| FR-03 | 热度采样/衰减生效，冷热判定符合配置阈值 | 单元测试 + 模拟 | Phase 3 ✅ 已完成 |
| FR-04 | 迁移期间读写不阻塞、可重试、数据最终一致 | 集成 + 故障注入 | Phase 6 ✅（异步队列 + 重试 + 恢复） |
| FR-05 | 重启后 WAL 回放恢复数据；Bitcask/LSM 均可读写 | 恢复测试 | Phase 4 ✅（WAL 恢复）+ Phase 5 ✅（SSTable 冷层 + Flush/恢复） |
| FR-06 | 1k/10k/100k 连接可建立并稳定服务 | 压力测试 | Phase 7/9 |
| FR-07 | mmap 路径无多余用户态拷贝 | 基准对比 | Phase 8 |
| FR-08 | 无全局锁；并发写不同 key 不串行 | 并发测试 | Phase 7 |
| FR-09 | Bloom Filter 显著降低不存在键读放大 | 基准 | Phase 5 ✅（FPR 0.82%） |
| FR-10 | Memory Pool 命中率高，GC 压力受控 | 基准 + JFR | Phase 8 |
| NFR-01 | 热点 GET P50 < 0.5ms；P95/P99 建立基线 | 压测 | Phase 9 |
| NFR-02 | 100k 连接下无连接泄漏、无 OOM | 压测 | Phase 9 |
| NFR-03 | 内存占用较纯内存 Redis 降低 60%–80% | 对比压测 | Phase 9 |

未列出的验收项在对应 Phase 的任务文件中补充（.codex/tasks/）。

Phase 2 内存核心验收：PUT / GET / DELETE / EXISTS / 迭代 / TTL / 内存配额 /
100 线程并发由 storage 测试套件覆盖（`mvn test` 79 用例全绿，2026-08-10）。
