# TSO 跨地域容灾（ADR-0223）

## 背景

Phase 43 的 TsoService 为单点部署。Phase 44 提供主备容灾：切换后
时间戳单调、恢复不回退。

## 设计

```text
主 TSO --allocate--> syncedWatermark --同步--> 备 TSO
故障切换：standby.restore(syncedWatermark) → STANDBY_ACTIVE
原主恢复：primary.restore(standby.watermark()) → PRIMARY_ACTIVE
```

复用 TsoService.restore 语义：水位 + 分配游标双推进，切换后新分配
严格大于已同步水位。

## 验收

- 主备矩阵：批量分配 + 水位同步（30 项展开）；
- 切换矩阵：failover 后分配 > 已同步水位（15 项展开）；
- 恢复矩阵：recoverPrimary 后分配 > 备水位（25 项展开）；
- 并发分配单调（10 项展开）。
