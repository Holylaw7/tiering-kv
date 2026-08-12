# Phase 42 生产基准报告（v2.5 RC）

## 口径说明

本报告为 **JVM 进程内基准**（如实记录）；跨地域/容器/磁盘门禁
（TD-048/049、K8S-001、REL-001、BM-001/002、TD-051/054/059/060/063/066/069/072/075/078）
仍待 Linux Runner，收敛表见
[gate-convergence-v8.md](docs/deployment/gate-convergence-v8.md)。

## 结果

| 指标 | 规模 | 结果 |
| --- | --- | --- |
| Async Commit | 1K~10K | 1M~10M ops/s |
| 悲观锁 | 1K~10K | 1M~10M ops/s |
| Coprocessor | 1K~10K rows | 500K~10M rows/s |
| Leveled 执行 | 1K~10K | 500K~2.5M/s |
| 自治 PD 调度 | 1K~10K | 58.8K~196K ops/s |
| 拓扑发现 | 10K 轮 | 79 ms |

## 目标对照

| 目标 | 状态 |
| --- | --- |
| Leveled 执行 | ✅ 本地完成（TD-077 关闭方向） |
| 悲观事务 | ✅ 本地完成 |
| Async Commit + resolved-ts | ✅ 本地完成 |
| Coprocessor 下推 | ✅ 本地完成 |
| 自治 PD 调度 | ✅ 本地完成 |
| 拓扑自发现 | ✅ 本地完成 |
| 真实执行门禁 | ⏳ Linux Runner 待执行 |
