# 全局一阶段提交规模化（ADR-0221）

## 背景

Phase 43 的 CrossRegionOnePhaseCommit 只做主副本资格判定。Phase 44
扩展到 3 地 / 5 地全局规模，同时保持「任一区域不合格回退 2PC」。

## 设计

```text
registerPrimaryReplica(region, eligible)
commit(txnId, regions[, commitTs])
  ├─ 全部区域 eligible → onePhase=true（可选推进 resolved 水位）
  └─ 任一不合格 → onePhase=false（调用方回退 2PC）
commitTwoPhase(txnId, regions) → 显式两阶段
completed[txnId] → 幂等去重
```

## 联动

- AsyncCommitCoordinator：单区路径不变，跨区走全局资格判定；
- resolved-ts：一阶段成功后 `advance(commitTs)`，单调推进；
- 幂等：同一 txnId 重复提交返回首次结果。

## 验收

- 规模化矩阵：3 地 / 5 地 / 10 地（75 项展开）；
- 回退矩阵：任一不合格 → two-phase；
- resolved-ts 联动矩阵：一阶段推进、回退不推进（8 项展开）。
