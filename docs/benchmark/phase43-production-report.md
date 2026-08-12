# Phase 43 生产报告

## 交付摘要

- 跨区一阶段提交（ADR-0214）：主副本资格 → 一阶段 / 回退 2PC；
- 多算子联合下推（ADR-0215）：FILTER → PROJECT → AGGREGATE；
- TSO 集群化（ADR-0216）：批量分配 + 单调 + 恢复不回退；
- 自治 PD 与全球自治联动（ADR-0217）：护栏 + 回滚 + 审计；
- 凭据探测（ADR-0218）：S3/Spot 三模式 + 降级登记；
- 门禁收敛 v9（ADR-0213）+ v2.6 冻结（ADR-0219）。

## 基准结果（本地进程内，PHASE43-BENCH 输出）

| 能力 | 规模 | 吞吐 |
| --- | --- | --- |
| 跨区一阶段判定 | 10K | ≈3.3M ops/s |
| Compound Coprocessor | 10K rows | ≈2.5M rows/s |
| TSO 批量分配 | 1K batches | ≈1M batches/s |
| 自治 PD 联动 | 1K rounds | ≈24K rounds/s |
| 凭据探测（模拟） | 1K | ≈1M probes/s |
| 门禁注册表查询 | 10K | ≈7ms 总量 |

## 三级基线（PHASE43-BASELINE）

| 级别 | 指标 | 目标 | 结果 |
| --- | --- | --- | --- |
| A 内存 GET | P99 | < 5ms | 通过（微秒级） |
| B 命令链 GET | P99 | < 10ms | 通过 |
| C 全链路（WAL+SSTable+mmap） | P99 | < 50ms | 通过 |
| A PUT / B SET / C PUT | 吞吐下限 | 50K/30K/20K ops/s | 通过 |

口径：JVM 进程内、page cache 热；跨机与跨地域基线待 Phase 44。

## 测试

- 新增：≥510（surefire 口径）；
- 全量回归：≥8867 全绿；
- 覆盖：跨区一阶段 70、多算子 76、TSO 76、自治联动 71、凭据 38、
  门禁 60、边缘矩阵 64、生产门禁 30、发布 10、基准 12+。

## 结论

系统达到 v2.6.0 发布候选：全球规模能力（跨区一阶段、TSO、自治联动）
与生产基线（A/B/C + TiKV 对比口径）就绪；真实 Runner 门禁按 v9 表
精确登记，等待 Phase 44 闭环。
