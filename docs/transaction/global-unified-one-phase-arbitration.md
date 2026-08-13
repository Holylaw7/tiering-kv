# 跨云一阶段全球统一（ADR-0242）

## 背景

Phase 46 的 MultiCloudOnePhaseScaleOut 依赖调用方提供固定拓扑。
Phase 47 实现任意云 × 区拓扑自动发现与动态仲裁。

## 设计

```text
registerZone(cloud, zone, eligible) → 拓扑版本 +1，缓存失效
commit(txnId, clouds[, commitTs])
  ├─ 自动发现：clouds 中每云的区结构从注册表聚合
  ├─ 区内多数 → 云级合格；云级多数 → 一阶段
  └─ 任一层次不合格 → 回退 2PC
cacheKey = txnId|v{topologyVersion}|sortedClouds → 幂等
```

## 联动

- MultiCloudOnePhaseScaleOut / MultiCloudOnePhaseCommit：资格模型复用；
- AsyncCommitCoordinator：单云路径不变；
- resolved-ts：一阶段成功后 advance(commitTs)。

## 验收

- 任意拓扑矩阵：1–9 云 × 1–6 区（35 项展开）；
- 动态仲裁：拓扑版本参与幂等（20 项展开）；
- resolved-ts 联动（5 项展开）；云计数（8 项展开）。
