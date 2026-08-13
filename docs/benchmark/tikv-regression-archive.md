# TiKV Regression Archive

## 设计

回归归档执行器（ADR-0260）在既有生产基线之上增加归档能力：

```text
多机部署回归
    ↓
BaselineSnapshot（GET/SET P50/P95/P99、吞吐、内存、RTT/RTO/RPO）
    ↓
趋势点（Trend）+ 告警历史（Alert）
    ↓
归档报表（CSV 导出）
```

## 口径说明

| scope | 含义 |
| --- | --- |
| LOCAL | 本地进程内测量（page cache 热，仅对比基线） |
| CROSS_MACHINE | 真实多机 Runner 测量（待执行） |
| PENDING | 未执行项，禁止伪报 |

每个快照携带 `evidence` 字段，报表中如实输出，避免把本地口径
当作跨机结论。

## 接入点

`io.tieringkv.benchmarks.ProductionBaselineRegressionArchive`，
测试见 `Phase49ProductionBaselineTest`；CI 基准 job 接入
`Phase49BenchmarkTest` / `Phase49ProductionBaselineTest`。
