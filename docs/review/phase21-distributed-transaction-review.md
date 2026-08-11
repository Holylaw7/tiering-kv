# Phase 21 评审报告：分布式事务网络化与云生产

Phase 21 · 2026-08-11

## 1. 总体结论

Phase 21 把 Phase 19/20 的进程内事务升级为跨节点分布式事务：

- `DistributedTxnRouter` + `RegionTxnClient` + `TxnParticipantClient`
  （PREWRITE/COMMIT/ROLLBACK/HEARTBEAT 走 MultiRaftEndpoint 单端口 RPC）；
- `TransactionParticipant` 每 Region 独立状态机
  （LOCKED → PREPARED → COMMITTED / ROLLED_BACK），全部 RPC 幂等；
- `TransactionMetadataService`：Raft 组 `txn_meta_region` + 本地日志，
  Coordinator 崩溃可恢复续跑；
- `MvccCompactor` 在线压缩（SafePoint 合并 + 原子索引文件）；
- 可观测性：txn_prepare/network_retry/lock_wait/region_count/recovery_time +
  INFO TRANSACTION；
- 真实 Docker 容器混沌（netem/分区/kill -9）执行成功。

## 2. ADR

| ADR | 决策 |
| --- | --- |
| 0083 | 分布式事务协议：RPC 2PC + participant 状态机 + 幂等 |
| 0084 | 事务元数据 Raft：REGISTER/PREPARE/COMMIT/ROLLBACK 全局视图 |
| 0085 | MVCC 在线压缩：SafePoint 合并 + 原子索引文件 |
| 0086 | 跨机混沌：Docker + tc netem + 分区 + kill -9 |

## 3. 实现

- `transaction/`：DistributedTxnRouter、RegionTxnClient、TxnParticipantClient、
  TransactionParticipant、TransactionMetadataService、TxnMetadataRaftGroup、
  TxnRpcCodec/TxnMessages/TxnRpcException；
- `cluster/rpc`：RpcMessageType TXN_*、TxnRpcHandler、MultiRaftEndpoint
  单端口事务分发；
- `mvcc/compaction`：MvccCompactor（批量删除 + 原子 move 索引文件 + 后台调度）；
- 指标：prepare/网络重试/锁等待/Region 数/恢复时间 + mvcc_compaction_*。

## 4. 测试

新增 202 项（目标 ≥200 ✅）：

| 分类 | 数量 | 结果 |
| --- | --- | --- |
| 分布式路由（TCP 三节点） | 17 | ✅ |
| 网络 2PC 故障 | 52 | ✅ |
| 元数据 | 41 | ✅ |
| 分布式混沌 | 32 | ✅ |
| MVCC 压缩 | 37 | ✅ |
| 指标 | 17 | ✅ |
| 基准 | 6 | ✅ |

全量回归：以最终 `mvn test` 为准（0 failures）。

## 5. 基准

见 [phase21-report.md](../benchmark/phase21-report.md)：单区 58.7–116.4K、
多区 88.1–110.7K、恢复 0–0ms、leader 恢复 156–276ms，全部达标。

## 6. 真实跨机混沌（Goal 4）

- 3 节点 Docker Compose 启动成功（node1/2/3，r1/r2 组）；
- `tc netem delay 100ms loss 5% duplicate 2%` 注入成功并保持存活；
- node1 网络分区/恢复：全部节点存活并回集群；
- node2 `kill -9`：node1/node3 保持多数派存活，node2 重启回集群；
- 修复了容器构建三个真实缺陷：netty-tcnative classifier（移除 go-offline）、
  jar 缺 Main-Class、jar 非 fat（shade）；
- 未执行：disk slow / disk full（需 IO 限流/文件系统注入）→ TD-044。

## 7. 局限（不隐藏）

1. TD-043（部分）：TCP 事务协议已由 CrossNodeTransactionTest 覆盖，
   但容器运行时尚未托管事务 participant/网关，跨机事务端到端待 Phase 22；
2. TD-044：disk slow / disk full 混沌未执行；
3. 基准为本地传输路径（不含 TCP 编解码），TCP 端到端吞吐未纳入；
4. 元数据日志先落盘后提案：Raft 失败时本地记录可能先于共识生效
   （at-least-once 语义，恢复以本地为准）。
