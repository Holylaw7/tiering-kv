# Phase 39 生产基准报告（v2.2 RC）

## 口径说明

本报告为 **JVM 进程内基准**（如实记录）；跨地域/容器/磁盘门禁
（TD-048/049、K8S-001、REL-001、BM-001/002、TD-051/054/059/060/063/066/069）
仍待 Linux Runner，收敛表见
[gate-convergence-v5.md](docs/deployment/gate-convergence-v5.md)。

## 结果

| 指标 | 规模 | 结果 |
| --- | --- | --- |
| 多智能体聚合 | 1K~10K | 250K~2.5M ops/s |
| 链上锚定 | 1K~10K | 62.5K~178.6K ops/s |
| 自动分层 | 1K~10K | 1M~10M ops/s |
| Spot 预测 | 1K~5K | 1M~5M ops/s |
| 自适应加固 | 1K~5K pairs | 131.6K~200K pairs/s |
| Pareto 前沿 | 10K 轮 | 14 ms |

## 目标对照

| 目标 | 状态 |
| --- | --- |
| 多智能体自治 | ✅ 本地完成 |
| 自动分层 | ✅ 本地完成 |
| 链上锚定 | ✅ 本地完成 |
| Spot 市场预测 | ✅ 本地完成 |
| 自适应加固 | ✅ 本地完成 |
| Pareto 容量 | ✅ 本地完成 |
| 真实执行门禁 | ⏳ Linux Runner 待执行 |
