# 三地五中心指南

Phase 29 · ADR-0123

## 拓扑

```text
2 PRIMARY（a/b）+ 2 SECONDARY（c/d）+ 1 OBSERVER（仲裁 e）
```

## 切换

- 任一主/备故障可 failover（仲裁不可切换）；
- 双主故障可连续 failover；
- DrDrillRunner 采样 RTO/RPO。

## 混沌

`FiveRegionChaosTest`：单主/双主/非仲裁故障矩阵 + 全球读一致性。
