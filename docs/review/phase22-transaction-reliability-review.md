# Phase 22 评审报告：事务可靠性与生产运行时

Phase 22 · 2026-08-11

## 1. 总体结论

Phase 22 将分布式事务从“协议正确”推进到“生产可靠运行”：

- 元数据决策排序（ADR-0087）：decisionIndex + Raft-first，消除本地日志
  先行窗口；恢复覆盖“元数据 COMMITTED 但 participant 未提交”的崩溃窗口；
- 生命周期管理（ADR-0088）：TTL / max-duration / 心跳续约 / 超时自动
  abort（无永久锁）；
- 分布式锁解析（ADR-0089）：orphan / primary / secondary / 状态缓存；
- 运行时（ADR-0090）：TCP 端到端事务链路 + participant 重启恢复；
- 磁盘故障语义（TD-044 部分关闭）：disk full / slow / WAL corrupt /
  readonly 的 in-JVM 测试覆盖。

## 2. ADR

| ADR | 决策 |
| --- | --- |
| 0087 | 决策排序：Raft-first + decisionIndex + 恢复补完 COMMITTED |
| 0088 | TTL/心跳：TxnTimeoutScheduler + TxnHeartbeatManager |
| 0089 | 锁解析：DetectLock → CheckPrimary → Resolve |
| 0090 | 运行时部署：compose.transaction + TCP 端到端 |

## 3. 实现

- metadata：TxnMetaEntry.decisionIndex、Raft-first propose、
  recoverFromRaft；
- lifecycle：TransactionLifecycleManager / TxnTimeoutScheduler /
  TxnHeartbeatManager；配置 txn.ttl-seconds / max-duration-seconds；
- lock：LockResolver / TxnStatusCache；
- metrics：txn_expired_total / long_running / abort_reason /
  lock_total / lock_resolve_total / lock_wait_seconds；
- 测试：Lifecycle 22 / LockResolver 21 / MetadataOrdering 19 /
  DiskChaos 21 / Metrics 14 / Runtime 8 / Benchmark 4（以全量回归计数为准）。

## 4. 基准

见 [phase22-report.md](../benchmark/phase22-report.md)：SET 128–150K、
GET 3.9–25M、跨区 33.6–59.7K、恢复 0–15ms、锁解析 50–129ms，全部达标。

## 5. 真实容器验证

- 三节点容器启动、netem/分区/kill -9 存活恢复（沿用 Phase 21 证据）；
- 磁盘注入受限：VM 数据盘 930G（dd 无法快速填满）、root 绕过目录权限位、
  WAL 截断路径未命中；in-JVM 磁盘故障语义测试兜底（TD-046）。

## 6. 局限（不隐藏）

1. TD-045：Phase 22 新增测试数低于 220 目标（以全量回归精确计数），
   缺口为参数化/等价场景扩展，后续补齐；
2. TD-046：真实容器 disk full / readonly / slow io 注入受 Docker Desktop
   权限限制未完成；
3. TD-043 部分关闭：TCP 端到端已覆盖；容器运行时托管 Gateway/Participant
   的完整链路仍待 Phase 23；
4. 决策窗口：participant 已提交而元数据未终态化仍存在，由幂等恢复兜底。
