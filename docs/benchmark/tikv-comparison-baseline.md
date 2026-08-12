# 生产级 Benchmark 对比 TiKV（ADR-0225）

## 口径声明

本基线为**本地进程内**口径（JVM 单机、page cache 热、无跨机网络），
与 TiKV 公开数据对比仅作数量级参考，不作为等效实测。**跨机待执行**
（BM-001/002）由 Phase 45 Runner 完成。

## A/B/C/D 四级基线

| 级别 | 路径 | 指标 |
| --- | --- | --- |
| A | MemTable 直连 | PUT 吞吐 > 50K ops/s；GET P99 < 5ms |
| B | CommandEngine 命令链 | SET 吞吐 > 30K ops/s；GET P99 < 10ms |
| C | WAL + SSTable + mmap | PUT > 20K ops/s；GET P99 < 50ms |
| D | 全局一阶段 + WAL + SSTable + mmap（多副本模拟） | 提交 P99 < 1ms；全算子链 > 50K rows/s |

## 对比 TiKV（公开口径，仅参考）

| 指标 | TiKV 公开参考 | Tiering-KV 本地进程内（A/B/C/D） |
| --- | --- | --- |
| 单点读延迟 | 亚毫秒 ~ 毫秒级 | A 级 P99 微秒级（内存热路径） |
| 写吞吐 | 万 ~ 十万 ops/s（取决于 fsync/副本数） | C/D 级 buffered WAL 十万级 |
| 冷读 | 依赖 block cache / page cache | C/D 级 mmap + OS page cache |
| 分布式事务 | 2PC/Raft 多副本 | D 级全局一阶段 + 回退 2PC（进程内模拟） |

差异来源：TiKV 为跨机 Raft 多副本持久化系统；Tiering-KV 本基线为
单机进程内链路，跨机口径必须等真实 Runner。

## 指标

- 延迟：GET/SET P50 / P95 / P99；
- 吞吐：ops/s、rows/s；
- 内存：entry 数 × 平均字节估算，与全内存 Redis 对比的节省百分比
  需跨机同负载实测。

## 运行

```bash
mvn -q test "-Dtest=Phase44ProductionBaselineTest"
```

输出前缀 `PHASE44-BASELINE`，作为 v2.7 发布流水线回归基准。
