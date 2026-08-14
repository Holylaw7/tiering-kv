# Phase 61 — v4 M4 Production Closure Report

## 口径与范围

ADR-0322 生产收口第一/二批交付：

- CapacityModel（TD-019）：四维容量估算（可计算可测试）；
- Operator 状态机：Provisioning → Ready → Upgrading / BackingUp /
  Restoring 转换矩阵 + 控制器集成；
- Jepsen 外部化：scripts/jepsen-run.sh（容器故障注入 +
  VerificationHarness 独立进程线性一致性回归）+ Runner job；
- 冷/热性能基线（TD-009）：scripts/cold-cache-bench.sh +
  ColdCacheBenchmarkTest（进程内口径；root 时 drop caches 覆盖
  OS 页缓存口径）。

## 冷/热基准结果（本地基线）

| 指标 | 值 |
| --- | --- |
| 20K × 64 维向量 mmap 全量读取（冷，空 BlockCache） | 119.842 ms |
| 20K × 64 维向量 mmap 全量读取（热，BlockCache 预热） | 18.924 ms |
| BlockCache 加速 | 6.3x |

结论：BlockCache 对向量索引 mmap 读取路径收益显著；OS 页缓存口径由
cold-cache-bench.sh（root drop caches）在 Linux Runner/本地覆盖。

## 容量模型示例

输入 QPS=10K / 值 1KB / 读 80% / 3 副本 / 保留 7 天 / 100 万活跃键：

- 内存 ≈ 3.36 GB（活跃键 × (值 + 96B) × 副本）；
- 磁盘 ≈ 3.72 TB（写占比 × 值 × 天数 × 86400 × 副本）；
- 吞吐预算 = 12K QPS（20% headroom）；延迟预算 = 5ms（读为主）。

公式与常数随基准数据校准（CapacityModelTest 固化）。

## Jepsen 外部化

```bash
scripts/jepsen-run.sh run   # 故障注入 ×4 + 线性一致性回归
scripts/jepsen-run.sh cleanup
```

每次故障（kill-coordinator / kill-participant / kill-meta / partition）
后运行 VerificationHarness（8 线程 × 300 ops）独立进程校验，非线性
即失败；报告输出 target/jepsen-report.txt，CI job jepsen-e2e 执行。

## 复现

```bash
scripts/cold-cache-bench.sh both
mvn -Dsurefire.excludedGroups= -Dtest=ColdCacheBenchmarkTest \
  -DfailIfNoTests=false test
```
