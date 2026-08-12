# Coprocessor 多算子联合下推（ADR-0215）

## 背景

Phase 42 的 Coprocessor 只支持单算子（TD-080）。存储层下推需要
FILTER → PROJECT → AGGREGATE 算子链，且结果必须与上层 SQL 一致。

## 设计

`CompoundCoprocessorRequest` 携带有序算子链；`CoprocessorExecutor`
按链顺序应用：

```text
range scan（[startKey, endKey)）
  → FILTER（value >= threshold）
  → PROJECT（value * threshold）
  → AGGREGATE（sum）
```

`executeCompound` 复用单算子 `execute`，无重复实现；上层 SQL 与下推路径
共享同一算子语义，一致性由等价性测试锁定。

## 验收

- 算子链矩阵：全部二元组合 + 链长 1–5（76 项展开）；
- 与上层 SQL 一致：FILTER / PROJECT 后 FILTER 等价性；
- 范围语义：endKey 排除；
- 空输入：AGGREGATE 返回 sum=0。
