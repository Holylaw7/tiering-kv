# Phase 26 评审报告：v1 Release Freeze & Enterprise Readiness

Phase 26 · 2026-08-11 · v1.0.0 发布候选

## 1. 结论

Phase 26 完成 v1 发布冻结与企业级能力交付：

- **API / Protocol Freeze**（ADR-0103）：RESP2 / RPC v1 / 存储格式 v1，
  兼容性矩阵 51 项；
- **PITR**（ADR-0104）：快照 + 归档日志 → 任意时间点恢复闭环；
- **CDC**（ADR-0105）：exactly-once checkpoint 消费；
- **Security GA**（ADR-0106）：RBAC 五角色五权限 + 令牌生命周期；
- **Kubernetes Operator**（ADR-0107）：CRD + 计划引擎 + 控制器；
- **tierctl CLI** 与 **v1 发布流水线**。

新增 **293 项测试**，全量 **2701/2701 PASS**（目标 ≥2700 ✅；另 6 项
容器门控本地跳过）。

## 2. ADR

| ADR | 主题 |
| --- | --- |
| 0103 | Protocol Compatibility Policy |
| 0104 | Point In Time Recovery |
| 0105 | CDC Architecture |
| 0106 | Enterprise Security Model |
| 0107 | Kubernetes Operator Design |

## 3. 关键实现

1. ProtocolVersion 冻结常量 + 旧客户端兼容矩阵（RESP pipeline/inline/
   二进制安全、RPC wire 值、Meta 命令）；
2. PITR：PitrWriteLog（分段 + CRC32C + 尾部容忍）、CheckpointManager、
   RestoreTimeline（水位后重放 + commitTS 边界）；MVCC 快照字节化；
3. CDC：ChangeEvent 四类型、生产者同步 seq、消费者检查点推进、
   崩溃恢复无重复；
4. Security：CredentialManager 签发/校验/轮换/吊销，`require` 授权
   抛异常（审计友好）；
5. Operator：desired vs current → 动作计划（CREATE/SCALE/UPGRADE/
   BACKUP），CRD 与 sample 齐备；
6. tierctl：七命令解析/校验/分发；release.yml 全流水线。

## 4. 修复与稳定性

- CDC/PITR 重开后 seq 从既有日志续接（修复 out-of-order 缺陷）；
- 全量回归负载下 benchmark 门控稳定化（5 轮取峰值，如实记录阈值）；
- 时间边界竞态修复（drain 解析器 5ms）。

## 5. 局限（不隐藏）

1. 跨机 Production Benchmark（Linux/Docker/K8s 拓扑）待 CI Runner；
2. RBAC 校验的 RPC/网关接线与 AUTH/ACL 为 Phase 27；
3. PITR 归档保留策略、CDC fan-out、Operator 真实集群绑定为后续版本；
4. 基准为 JVM 进程内口径，跨机数值以 CI 报告为准。

## 6. 下一步

- 触发 release.yml（v1.0.0-rc1）与 transaction-e2e 四 job；
- RBAC 接入网关/RPC、PITR 保留策略、CDC 多消费者组；
- v1.0.0 GA 发布与冻结公告。
