# Geo Distributed Transaction 设计

Phase 27 · ADR-0109

## 1. 架构

```text
GeoTransactionCoordinator
  ├─ GeoDecisionLog（决策先行，CRC 持久化）
  ├─ GeoRegionTxnClient（远程 participant，幂等重试）
  └─ GeoRpcTransport（prewrite/commit/rollback）
```

决策语义与 v1 一致（元数据 Raft 决策不在此层变更），participant 远程化。

## 2. 恢复

- 区域故障时，未决事务按 GeoDecisionLog 重放；
- COMMIT 决策补提交，ROLLBACK 决策幂等回滚；
- 重复提交由 participant 状态机（ALREADY）兜底。

## 3. 时间戳

协调器使用单调时钟保证 `startTS < commitTS`，避免 provisional 删除误删
同时间戳已提交版本（与 HLC 语义一致）。

## 4. 限制

- 跨地域 RTT 进入提交延迟；
- 双向复制（多主）为 Phase 28 方向。
