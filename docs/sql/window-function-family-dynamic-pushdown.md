# 窗口函数全族 / 动态下推（ADR-0236）

## 背景

Phase 45 支持 ROW_NUMBER/RANK 与静态成本模型。Phase 46 扩展到窗口
全族与运行时动态下推。

## 设计

```text
WINDOW（按 key 分区、value 升序）
  ├─ ROW_NUMBER / RANK：编号与排名
  ├─ LAG / LEAD：前/后行取值（边界为 0）
  └─ SUM / COUNT / AVG OVER：分区前缀聚合

DynamicPushdownPlanner
  ├─ record(rows, transferBytes, elapsedNanos) → EWMA 每行传输成本
  └─ shouldPushdown(rows, localBytesPerRow, transferBytesPerRow)
       → 历史传输成本 vs 本地扫描；低于 minRows 不下推
```

## 验收

- 窗口全族矩阵：6 函数 × 分区/组内行/偏移（35 项展开）；
- 与上层 SQL 一致：SUM/COUNT/AVG 前缀关系（等价性）；
- 动态规划：EWMA 更新 + 决策矩阵（10 项）+ alpha（6 项）；
- 分区矩阵：分区数 × 组内行（20 项展开）。
