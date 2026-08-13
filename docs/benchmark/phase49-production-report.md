# Phase 49 Production Report

## 口径

本报告为**本地进程内（LOCAL）口径**，用于回归与趋势；跨机/跨地域
（CROSS_MACHINE）口径待真实 Runner 执行后补充，禁止把本地数字
当作跨机结论。

## 基准摘要

| 能力 | 测量 | 口径 |
| --- | --- | --- |
| 跨监管域联邦仲裁 | 100K commit/s 量级 | LOCAL |
| 联邦学习 | 100K learn/s 量级 | LOCAL |
| 商用授时设备读取 | 100K read/s 量级 | LOCAL（模拟设备） |
| 法规差异计算 | 50K diff/s 量级 | LOCAL |
| 门禁收敛表查询 | 100K lookup/s 量级 | LOCAL |

详细数字以 `Phase49BenchmarkTest` 输出
`PHASE49-BENCH-*` 行为准（surefire stdout）。

## 回归归档

`ProductionBaselineRegressionArchive` 记录快照/趋势/告警；Phase 49
本地快照入库，跨机快照标记 PENDING。
