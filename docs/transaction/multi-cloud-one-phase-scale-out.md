# 跨云一阶段规模化（ADR-0235）

## 背景

Phase 45 的 MultiCloudOnePhaseCommit 只做云级仲裁。Phase 46 扩展到
云 × 区混合拓扑，分层仲裁覆盖两级故障域。

## 设计

```text
registerZone(cloud, zone, eligible)
markCloudUnavailable(cloud) → 全区降级
commit(txnId, topology[cloud → zones])
  ├─ 每云：区内合格 > zones/2 → 云级合格
  ├─ 云级：合格云 > clouds/2 → 一阶段
  └─ 任一层次不合格 → 回退 2PC
completed[txnId|sortedTopology] → 幂等
```

## 联动

- MultiCloudOnePhaseCommit / GlobalOnePhaseCommit：资格模型复用；
- AsyncCommitCoordinator：单云路径不变；
- resolved-ts：一阶段成功后 advance(commitTs)。

## 验收

- 混合拓扑矩阵：1–9 云 × 1–6 区 × 合格区（35 项展开）；
- 分层仲裁矩阵：区失败拖垮云、云不可用降级；
- resolved-ts 联动（5 项展开）；幂等（20 项展开）。
