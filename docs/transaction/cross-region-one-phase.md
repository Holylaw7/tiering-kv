# 跨区一阶段提交（ADR-0214）

## 背景

Phase 42 的 async commit 是单区一阶段（TD-079）。跨区事务需要：

- 主副本所在区域全部具备一阶段资格时走一阶段；
- 任一区域主副本不合格时必须回退 2PC，禁止降级为不安全提交。

## 设计

```text
commit(txnId, regions)
  ├─ 全部区域 primaryReplica eligible → onePhase=true
  └─ 任一不合格 → onePhase=false（调用方走 2PC）

commitTwoPhase(txnId, regions) → 显式两阶段（幂等由调用方保证）
```

## 与 AsyncCommitCoordinator / resolved-ts 联动

- 单区路径保持原有 AsyncCommitCoordinator 语义；
- 跨区路径新增 `CrossRegionOnePhaseCommit`，只做资格判定与回退选择，
  不修改事务状态机；
- resolved-ts 联动维持单调推进，跨区一阶段成功后同样推进已解析水位。

## 验收

- 一阶段矩阵：eligible 覆盖 / 部分覆盖 / 未覆盖（70 项展开）；
- 回退矩阵：任一不合格 → two-phase，成功语义保持；
- 幂等：重复 commit 返回一致结果（15 项展开）。
