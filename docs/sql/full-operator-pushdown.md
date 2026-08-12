# Coprocessor 全算子联合下推（ADR-0222）

## 背景

Phase 43 支持 FILTER → PROJECT → AGGREGATE 链。Phase 44 扩展
JOIN / GROUP_BY / ORDER_BY / LIMIT，与上层 SQL 结果一致。

## 设计

固定链顺序（与用户声明顺序无关）：

```text
range scan → JOIN（等值内连接，value 相加）
  → FILTER（value >= threshold）
  → PROJECT（value * threshold）
  → AGGREGATE（全量求和）
  → GROUP_BY（按 key 分组求和）
  → ORDER_BY（value 升/降序）
  → LIMIT（截断）
```

同一算子出现多次时按次数重复应用（兼容旧链语义）；
`CompoundCoprocessorRequest` 扩展 joinRows / limit / orderDescending；
单算子 `execute` 对 JOIN/LIMIT 保持语义兼容（无第二表 / 无限截断）。

## 验收

- JOIN 矩阵：左右行数任意组合（30 项展开）；
- GROUP_BY 矩阵：分组数 × 每组行数（20 项展开）；
- ORDER_BY + LIMIT 矩阵：升/降序 + 截断（20 项展开）；
- 与上层 SQL 一致：FILTER + GROUP_BY 等价性。
