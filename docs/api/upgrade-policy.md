# 升级策略

Phase 26 · ADR-0103

## 1. 升级前置

1. 备份元数据快照 + MVCC 索引（PITR 检查点）；
2. 确认集群 `/readiness` 就绪；
3. 滚动升级逐节点替换（quorum ≥2，PDB 保护）。

## 2. 升级后

1. `recover()` 补完决策；
2. 运行 `ProtocolCompatibilityTest` 验证旧客户端；
3. 观察 CDC checkpoint 无回退、PITR 恢复点有效。

## 3. 回滚

- 回滚镜像版本（operator 更新 image）；
- 使用 PITR 恢复到升级前时间点；
- 升级前备份保留期内可完整恢复。
