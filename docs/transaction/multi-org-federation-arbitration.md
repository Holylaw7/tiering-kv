# 多组织联邦仲裁（ADR-0249）

## 背景

Phase 47 的 GlobalUnifiedOnePhaseArbitration 覆盖单组织。Phase 48
扩展到多组织边界。

## 设计

```text
registerOrganization(cloud, organization)
registerZone(cloud, zone, eligible)
commit(txnId, clouds[, commitTs])
  ├─ 组织内：合格云 > 组织云/2 → 组织合格
  ├─ 联邦：合格组织 > 总组织/2 → 一阶段
  └─ 任一组织不合格 → 回退 2PC
cacheKey = txnId|v{federationVersion}|sortedClouds → 幂等
```

## 联动

- GlobalUnifiedOnePhaseArbitration / MultiCloudOnePhaseScaleOut：
  资格模型复用；
- AsyncCommitCoordinator：单云路径不变；
- resolved-ts：一阶段成功后 advance(commitTs)。

## 验收

- 联邦矩阵：1–8 组织 × 1–3 云/组织 × 1–6 区（35 项展开）；
- 组织仲裁：混合合格/不合格（13 项）；幂等（20 项展开）；
- resolved-ts 联动（5 项展开）；组织计数（8 项展开）。
