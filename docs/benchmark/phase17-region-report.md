# Phase 17 Region 生命周期基准报告

Phase 17 · 2026-08-10

## 1. 方法说明（如实声明）

- split/merge：进程内 MemTable（200K 键实测，1M 为线性外推）；
- leader transfer：3 节点进程内 Raft（TimeoutNow 真实交接）；
- Redis 网关：handler 级吞吐（RESP 编解码不含在内）；
- 并行迁移：进程内按段并行（8 worker）。

## 2. Region Split

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| 200K 键 | 0.186 s（1.08M entries/s） | — | ✅ |
| 1M 键（外推） | ~0.9 s | <10 s | ✅ |

## 3. Region Merge

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| 200K 键 | 0.142 s（1.41M entries/s） | — | ✅ |
| 1M 键（外推） | ~0.7 s | <20 s | ✅ |

## 4. 并行迁移（TD-033）

| 条目大小 | Phase 16（单流） | Phase 17（并行 8 worker） | 目标 | 状态 |
| --- | --- | --- | --- | --- |
| 100B | 82.7 MB/s | 209.1 MB/s | >150 MB/s | ✅ |
| 1KB | 223.1 MB/s | 986.0 MB/s | >300 MB/s | ✅ |
| 10KB | 631.0 MB/s | 1952.1 MB/s | — | ✅ |

实现：MemTable 按段分片（`segmentIterator`）+ 每 chunk 独立快照与
检查点 + 零拷贝批写；chunk 级 CRC/retry/pause-resume。

## 5. 真实 Leader Transfer

| 指标 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| 交接耗时 | 24 ms | <500 ms | ✅ |

200ms 延迟 + 10% 丢包混沌下交接仍成功（<5s，见 RegionChaosTest）；
交接要求目标日志追平；pending proposal 经冲突截断显式失败。

## 6. Redis Cluster Gateway

| 命令 | 实测 | 目标 | 状态 |
| --- | --- | --- | --- |
| GET | 3.68M ops/s | >100K | ✅ |
| SET | 1.67M ops/s | >50K | ✅ |

支持 GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS；非本地键返回
`MOVED slot host:port`（Redis Cluster 兼容）。

## 7. 复现

- `mvn -Dtest=Phase17RegionBenchmarkTest test`
- `mvn -Dtest=ParallelMigrationBenchmarkTest test`
- 输出前缀：`PHASE17-BENCH`
