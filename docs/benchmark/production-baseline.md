# 生产级 Benchmark 基线（ADR-0218）

## 口径声明

本基线为**本地进程内**口径（JVM 单机、page cache 热、无跨机网络），
与 TiKV 公开数据对比仅作数量级参考，不作为等效实测。跨机 Runner
基线（BM-001/002）待 Phase 44 执行。

## A/B/C 三级基线

| 级别 | 路径 | 指标 | 基线 |
| --- | --- | --- | --- |
| A | MemTable 直连（Storage API） | GET P50/P95/P99 | 见报告；P99 < 5ms |
| A | MemTable 直连 | PUT 吞吐 | > 50K ops/s（10K 规模实测约 1M+） |
| B | CommandEngine（RESP 命令链） | GET P50/P95/P99 | P99 < 10ms |
| B | CommandEngine | SET 吞吐 | > 30K ops/s |
| C | WAL → MemTable → SSTable → mmap | PUT / GET | P99 < 50ms；PUT > 20K ops/s |

## 对比 TiKV（公开数据，仅参考）

| 指标 | TiKV 公开参考（官方/社区文档） | Tiering-KV 本地进程内 |
| --- | --- | --- |
| 单点读延迟 | 亚毫秒 ~ 毫秒级 | A 级 P99 微秒级（内存热路径） |
| 写吞吐 | 万 ~ 十万 ops/s（取决于 fsync/副本数） | C 级 WAL buffered 口径十万级 |
| 冷读 | 依赖 block cache / page cache | C 级 mmap + OS page cache |

差异来源：TiKV 为跨机 Raft 多副本持久化系统；Tiering-KV 本基线为
单机进程内链路，跨机口径必须等 Phase 44 真实 Runner。

## 内存基线

10K × (key≈8B + value=64B) 估算 ≈ 1MB 量级（entry 固定开销估算 96B/条），
符合冷热分层「内存只留热数据」的目标；与 Redis 全内存对比的内存节省
百分比需要跨机同负载实测（BM-001）。

## 运行

```bash
mvn -q test "-Dtest=Phase43ProductionBaselineTest"
```

输出前缀 `PHASE43-BASELINE`，可作为发布流水线回归基准。
