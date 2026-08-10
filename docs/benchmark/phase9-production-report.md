# Phase 9 · Level C 生产全链路报告

环境：同上；拓扑 = 回环 Client → RESP → Netty → ShardExecutor → WAL
（EVERY_SEC）→ MemTable → 水位 Flush → SSTable（mmap + BlockCache）→
异步迁移；Workload A/B/C 各 100K 操作，64 连接 × pipeline 16。

| Workload | 组成 | ops/s | 每命令 P99 |
| --- | --- | --- | --- |
| A 纯缓存 | 90% GET / 10% SET | 115K | <0.01ms |
| B 普通 KV | 70% GET / 30% SET | 149–157K | <0.01ms |
| C 热点 | 90% 流量集中在 10 键 | 158–178K | <0.01ms |

目标：稳定吞吐模型 + P99 <5ms ✅（管道内每命令 µs 级；端到端 RTT 以
Level B 单请求口径为准）。

## Workload D（内存压力 → Flush/Migration/Compaction）

- 配额 1MB，20K 连续 SET：终态内存 398KB < 1MB ✅（背压 + 水位生效）；
- 冷层 SSTable = 2（迁移 + flush 均落盘）；GC 增量 ≈ 8；
- 迁移/背压链路在压力下保持内存受控、无异常。

## IO 概况（汇总）

- WAL append（buffered）P99 0.001–0.003ms（WAL 基准）；
- Flush 720–917K entries/s；合并 46.6MB/s（tiering/cold 基准）；
- SSTable 随机读 P99 0.012–0.048ms，BlockCache 命中率 94.8%（IO 基准）。

注：客户端与服务器同 JVM；独立进程复测与 10M 数据集为手动项（TD-016）。
