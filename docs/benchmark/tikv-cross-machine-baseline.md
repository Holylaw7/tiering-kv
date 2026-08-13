# TiKV 跨机对比基线（ADR-0232）

## 口径声明

本基线为**本地进程内**口径（JVM 单机、page cache 热），与 TiKV 公开
数据对比仅作数量级参考。**跨机待执行**：BM-001/002 部署脚本
（Gateway×3 / Metadata×3 / Storage×6）已就绪，由 Phase 46 Runner
执行；未执行项精确登记，禁止伪报。

## A/B/C/D 四级基线

| 级别 | 路径 | 指标 |
| --- | --- | --- |
| A | MemTable 直连 | PUT 吞吐 > 50K ops/s；GET P99 < 5ms |
| B | CommandEngine 命令链 | SET 吞吐 > 30K ops/s；GET P99 < 10ms |
| C | WAL + SSTable + mmap | PUT > 20K ops/s；GET P99 < 50ms |
| D | 跨云一阶段 + WAL + SSTable + mmap（进程内模拟） | 提交 P99 < 1ms；全算子链 > 50K rows/s |

## 对比 TiKV（公开口径，仅参考）

| 指标 | TiKV 公开参考 | Tiering-KV 本地进程内（A/B/C/D） |
| --- | --- | --- |
| 单点读延迟 | 亚毫秒 ~ 毫秒级 | A 级 P99 微秒级（内存热路径） |
| 写吞吐 | 万 ~ 十万 ops/s | C/D 级 buffered WAL 十万级 |
| 冷读 | block cache / page cache | C/D 级 mmap + OS page cache |
| 跨机 RTT/RTO/RPO | 多副本跨机 | 跨机待执行（BM-002） |
| 冲突率/收敛时间 | 多主复制 | 跨机待执行 |

## 指标

- 延迟：GET/SET P50 / P95 / P99；
- 吞吐：ops/s、rows/s；
- 内存：entry 数 × 平均字节估算；
- 跨机：RTT / RTO / RPO / 冲突率 / 收敛时间（待 Runner）。

## 运行

```bash
mvn -q test "-Dtest=Phase45ProductionBaselineTest"
```

输出前缀 `PHASE45-BASELINE`，作为 v2.8 发布流水线回归基准。
