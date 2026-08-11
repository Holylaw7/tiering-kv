# Phase 23 评审报告：事务运行时最终化

Phase 23 · 2026-08-11

## 1. 结论

Phase 23 完成事务运行时闭环：

- runtime 角色（gateway/coordinator/participant/metadata）独立 JVM 可部署；
- Gateway→Coordinator→Participant→Metadata 全链路 TCP（无 LocalTransport）；
- 生命周期状态持久化到元数据 Raft（ADR-0091）；
- LockResolver 分布式 RPC（CHECK/RESOLVE/HEARTBEAT，ADR-0092）；
- 磁盘故障 in-JVM/容器式语义验证（TD-046 部分关闭）；
- 新增 158 项测试，全量 **2007/2007**（TD-045 关闭）。

## 2. ADR

0091 生命周期持久化 / 0092 分布式锁解析 / 0093 生产运行时部署 /
0094 磁盘混沌验证。

## 3. 关键修复

- Router txnId 改为全局唯一：重启后的 Router 不再与已提交事务 id 冲突；
- RPC 类型 `txn()` 范围扩展至 TXN_GET，网关读路径可用；
- 元数据 Raft-first + decisionIndex（沿用 0087），恢复补完 COMMITTED。

## 4. 局限（不隐藏）

1. TD-048：compose.transaction 已提供，真实容器编排运行未执行
   （Docker 环境时间/权限限制）；
2. TD-049：真实 disk full/readonly/slow io 注入受 Docker Desktop 限制；
3. 元数据网络化为单节点服务（TXN_METADATA RPC），3 节点元数据 Raft
   仍需网络化传输（沿用 TD-047）。
