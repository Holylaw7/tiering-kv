# 多表 JOIN / 窗口函数下推（ADR-0229）

## 背景

Phase 44 支持单表 JOIN。Phase 45 扩展多表等值连接与窗口函数
（ROW_NUMBER/RANK），并加入下推成本模型。

## 设计

固定链顺序（与用户声明顺序无关）：

```text
range scan → JOIN（joinRows + joinTables 依次等值连接，value 相加）
  → FILTER → PROJECT → AGGREGATE → GROUP_BY
  → WINDOW（按 key 分区、value 升序；ROW_NUMBER / RANK）
  → ORDER_BY → LIMIT
```

`PushdownCostModel.shouldPushdown(rows, localBytesPerRow,
transferBytesPerRow)`：本地扫描 > 传输成本 → 下推。

## 验收

- 多表矩阵：主表 × 1–3 张附加表（35 项展开）；
- 窗口矩阵：分区 × 组内行 × 重复值 × 函数（20 项展开）；
- 与上层 SQL 一致：窗口编号 / RANK 语义等价性；
- 成本模型：决策矩阵（8 项）+ overhead（3 项）。
