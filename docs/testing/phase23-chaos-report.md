# Phase 23 混沌报告：运行时与磁盘故障

Phase 23 · 2026-08-11

## 1. 运行时混沌（ContainerTransactionRuntimeTest）

| 故障 | 验证 |
| --- | --- |
| Coordinator 重启 | 新 Router（全局唯一 txnId）恢复提交，无重复/丢失 |
| Participant 重启 | 同一端口重建 participant，已提交数据可读、新事务可写 |
| Metadata 重启 | 从镜像日志恢复决策状态 |
| Gateway 重启 | 网关无状态，重建即可（运行时角色支持） |
| 网络丢包 | 客户端重试 + recover 兜底，无丢失提交 |

## 2. 磁盘混沌（Phase23DiskChaosTest / TD-046）

| 故障 | 结果 |
| --- | --- |
| disk full on commit | 决策持久化 → 重启恢复补完，零提交丢失 |
| readonly fs | commit 失败 → 回滚，无脏数据 |
| slow disk | 提交一致，无 split brain |
| 参数化 1..160 次 put 故障 | 全部恢复或回滚，无丢失 |

真实容器注入（fallocate/mount/fio）仍受 Docker Desktop 权限限制 → TD-049。
