# 跨云全局一阶段提交（ADR-0228）

## 背景

Phase 44 的 GlobalOnePhaseCommit 覆盖同构区域。Phase 45 扩展到多云：
不同云提供商的区域同时参与，单云故障不阻塞提交。

## 设计

```text
registerCloud(cloud, eligible)
markUnavailable(cloud) → 降级为 2PC 参与方
commit(txnId, clouds[, commitTs])
  ├─ eligible > clouds/2 → onePhase=true（可选推进 resolved 水位）
  └─ 否则 → onePhase=false（调用方回退 2PC）
completed[txnId|sortedClouds] → 幂等
```

## 联动

- GlobalOnePhaseCommit：同构区域路径复用资格模型；
- AsyncCommitCoordinator：单云路径不变；
- resolved-ts：一阶段成功后 advance(commitTs)。

## 验收

- 仲裁矩阵：1–12 云 × 合格数（35 项展开）；
- 回退矩阵：少数云 / 不可用云 → two-phase；
- resolved-ts 联动：一阶段推进、回退不推进（5 项展开）；
- 幂等：txnId + 云集合去重（20 项展开）。
