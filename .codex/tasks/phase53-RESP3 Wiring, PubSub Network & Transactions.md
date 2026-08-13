# Phase 53 Task Prompt — RESP3 Wiring, Pub/Sub Network & Transactions

## 1. Context

Phase 52（v3.4.0 RC）补齐 hash/list/set/zset 命令族、RESP3 协议类型
与本地 Pub/Sub，命令注册表 90 个，全量回归 **13583/13583 全绿**。

Phase 52 评审登记的遗留（本阶段闭环）：

```text
1. RESP3 连接级接线未完成：HELLO 3 只换命令层，网络管道仍按 RESP2 编码；
2. Pub/Sub 无连接级投递：broker 有，但订阅者无法绑定连接；
3. 集群广播只有 SPI，无网络实现；
4. 高级数据结构命令缺失（HSCAN/ZRANGEBYLEX/LINSERT/LMOVE 等）；
5. MULTI/EXEC/DISCARD/WATCH 事务命令缺失。
```

Phase 53 目标：**RESP3 Wiring, Pub/Sub Network & Transactions
（v3.5.0 RC）**——RESP3 按连接接线（HELLO 3 切换编码器），Pub/Sub
连接级投递 + Netty 集群广播，补齐高级数据结构命令与 MULTI/EXEC
事务队列，并用网络/并发/事务测试闭环。

当前基线：

```text
develop   : d8c41dd merge: integrate Phase52 data structures resp3
            and pubsub
定位      : Enterprise-ready Distributed Database（v3.4.0 RC）
Tests     : 13583/13583 PASS（另 6 项容器门控本地跳过）
Commands  : 90（CommandRegistry）
```

## 2. Release 前置项（Phase 25–52 遗留，本阶段保持终态）

| 编号 | 内容 | 终态 | Phase 53 处置 |
| --- | --- | --- | --- |
| TD-048/049、K8S-001 | 容器/块设备/kind 门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| REL-001 / TD-075 | 发布流水线记录 | REGISTERED_RELEASE | 待真实 tag |
| BM-001/002 等跨机项 | 跨机/跨地域门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| TD-076 | 真实网络凭据 | ENV_BLOCKED_FINAL | 保持封板 |
| RESP3 连接接线 | Phase 52 登记 | 本阶段关闭 | Goal 1 |
| Pub/Sub 连接投递/广播 | Phase 52 登记 | 本阶段关闭 | Goal 2/3 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机、WAL/RPC 冻结
  格式；类型化编码 additive 维持；
- RESP3 必须按连接隔离：HELLO 3 只影响该连接编码，其他连接零影响；
- Pub/Sub 至少一次投递；集群广播 best-effort + 失败登记，禁止伪报
  送达；不落盘、不阻塞写路径；
- MULTI/EXEC 命令排队 + 批量原子应用（复用 applyBatch/事务路径），
  禁止破坏既有事务状态机（MVCC 2PC 路径不变）；
- WATCH 乐观并发为可选范围：实现版本守卫或在文档登记限制；
- 每阶段完成 `mvn test` 全量 0 failures（目标 ≥14140）。

## 3. Phase 53 Goal

目标：**RESP3 Wiring, Pub/Sub Network & Transactions（v3.5.0 RC）**，
完成 8 个 Goal：

1. RESP3 连接级接线（HELLO 3 → 连接编码器切换）
2. Pub/Sub 连接级投递（连接订阅者 + Push 消息）
3. 集群广播网络化（Netty PubSubForwarder RPC）
4. 高级数据结构命令（HSCAN/LINSERT/LMOVE/RPOPLPUSH/ZRANGEBYLEX 等）
5. MULTI/EXEC/DISCARD 事务队列与原子应用
6. 连接生命周期与清理（断线退订、协议状态重置）
7. 协议/命令兼容矩阵扩展（RESP3 线上往返 + 事务形态）
8. v3.5.0 RC 冻结与发布流水线

## 4. Goals

### Goal 1 — RESP3 连接级接线

目标：HELLO 3 按连接切换编码，RESP2 连接零影响。

交付：

- Netty 管道：连接级 `ConnectionProtocolState`（channel attr）；
- `CommandHandler`/`ClusterCommandHandler` 按状态选择
  RespEncoder.write / writeV3；
- HELLO 命令联动 channel attr 切换；HELLO 2 回退；
- 数据结构命令按协议版本返回：HGETALL（RESP3 Map / RESP2 平铺数组）、
  SMEMBERS（RESP3 Set / RESP2 数组）；
- 验收：双连接同服 RESP2/RESP3 并存矩阵全绿。

ADR：`ADR-0283 RESP3 Connection-Level Wiring`。

### Goal 2 — Pub/Sub 连接级投递

目标：订阅者绑定连接，消息经连接队列投递。

交付：

- 连接订阅注册：subscribe/psubscribe 时注册连接级 Subscriber；
- 投递格式：RESP3 Push（type=message/pmessage）；RESP2 数组兼容；
- 队列有界（背压丢弃登记，至少一次语义由重连补偿）；
- 验收：连接投递矩阵 + 模式矩阵 + 背压登记矩阵全绿。

ADR：`ADR-0284 Pub/Sub Connection Delivery`。

### Goal 3 — 集群广播网络化

目标：PubSubForwarder 的 Netty RPC 实现。

交付：

- `RpcPubSubForwarder`：peer 节点注册 + channel 消息转发；
- 环回抑制（不转发回来源节点）；best-effort + 失败登记；
- 与 MultiRaftEndpoint/RpcClient 复用（或独立轻量 RPC 帧）；
- 验收：转发矩阵 + 环回抑制矩阵 + 失败登记矩阵全绿。

ADR：`ADR-0285 Cluster Pub/Sub Forwarding`。

### Goal 4 — 高级数据结构命令

目标：补齐常用高级命令。

交付：

- HSCAN（游标 + 字段匹配）；
- LINSERT / LMOVE / RPOPLPUSH；
- ZRANGEBYLEX / ZLEXCOUNT / ZREMRANGEBYLEX；
- 全部走 `AtomicStringOps.update` 段锁原子；
- 验收：高级命令矩阵 + 边界矩阵全绿。

ADR：`ADR-0286 Advanced Data Structure Commands`。

### Goal 5 — MULTI/EXEC/DISCARD 事务队列

目标：命令排队 + 原子应用。

交付：

- 连接级事务状态（MULTI 开启 → 命令入队 QUEUED → EXEC 批量执行）；
- EXEC 复用 applyBatch（同段原子；跨段语义登记）；
- DISCARD 清空；错误入队返回 EXERR；
- WATCH：版本守卫实现或文档登记（二选一，须明确）。
- 验收：事务矩阵 + 原子性矩阵 + 错误矩阵全绿。

ADR：`ADR-0287 MULTI/EXEC Transaction Queueing`。

### Goal 6 — 连接生命周期与清理

目标：断线/协议切换的清理闭环。

交付：

- 连接关闭 → 退订全部 channel/pattern、清空事务队列、重置协议状态；
- 订阅计数与 broker 状态一致；
- 验收：生命周期矩阵全绿。

ADR：`ADR-0288 Connection Lifecycle & Cleanup`。

### Goal 7 — 协议/命令兼容矩阵扩展

目标：RESP3 线上往返与事务回复形态。

交付：

- TCP 往返 RESP3（HELLO 3 → HGETALL Map → 解码）；
- pipeline 下 Push 与普通回复保序；
- EXEC 回复数组、QUEUED 简单串、错误文本矩阵；
- 验收：线上矩阵全绿。

ADR：`ADR-0287/0288`（复用）。

### Goal 8 — v3.5.0 RC 冻结与发布流水线

目标：v3.5.0 RC。

交付：

- pom revision 3.5.0-SNAPSHOT；release.yml v3.5.0 标签 +
  Phase53BenchmarkTest/Baseline；
- `docs/release/v3.5.0-release-notes.md`；全量回归 ≥14140。

ADR：`ADR-0289 v3.5 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0283 | RESP3 Connection-Level Wiring |
| ADR-0284 | Pub/Sub Connection Delivery |
| ADR-0285 | Cluster Pub/Sub Forwarding |
| ADR-0286 | Advanced Data Structure Commands |
| ADR-0287 | MULTI/EXEC Transaction Queueing |
| ADR-0288 | Connection Lifecycle & Cleanup |
| ADR-0289 | v3.5 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=560 tests**（Phase 53，surefire 口径）；

Phase 1-53 全量目标：**>=14140 tests**（当前 13583）。

| Module | Count |
| --- | ---: |
| RESP3 连接接线 | 80 |
| Pub/Sub 连接投递 | 70 |
| 集群广播 RPC | 70 |
| 高级数据结构命令 | 100 |
| MULTI/EXEC 事务 | 100 |
| 连接生命周期 | 60 |
| 线上兼容矩阵 | 60 |
| 参数化边缘矩阵 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase53-resp3-wiring-pubsub-network-review.md
docs/protocol/resp3-connection-wiring.md
docs/operations/pubsub-network-delivery.md
docs/design/advanced-data-structure-commands.md
docs/transaction/multi-exec-guide.md
docs/operations/connection-lifecycle.md
docs/benchmark/phase53-production-report.md
docs/benchmark/network-latency-report.md
docs/release/v3.5.0-release-notes.md
docs/review/phase53-transaction-review.md
```

## 8. Engineering Rules

- RESP3 按连接隔离，禁止全局切换；
- Pub/Sub 至少一次 + best-effort 广播 + 失败登记；
- MULTI/EXEC 队列 + applyBatch 原子应用，不动 MVCC 2PC 状态机；
- 冻结格式不变；类型化编码 additive；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase53-resp3-wiring-pubsub-network-txn`

Commits：

```text
docs: add phase53 ADRs 0283-0289
feat(protocol): resp3 connection level wiring
feat(operations): pubsub connection delivery
feat(operations): cluster pubsub forwarding rpc
feat(command): advanced data structure commands
feat(command): multi exec transaction queueing
feat(operations): connection lifecycle cleanup
feat(ci): v3.5 release and gate convergence
docs: phase53 release
```

Merge：`merge: integrate Phase53 resp3 wiring pubsub network and transactions`

Checkpoint：`checkpoint-before-phase53` / `checkpoint-after-phase53`

## 10. Success Criteria

全部满足：

```text
✅ RESP3 按连接接线（HELLO 3 切换编码器，RESP2 连接零影响）
✅ Pub/Sub 连接级投递（Push 消息 + 有界队列 + 背压登记）
✅ 集群广播 Netty RPC（环回抑制 + 失败登记）
✅ 高级数据结构命令（HSCAN/LINSERT/LMOVE/ZRANGEBYLEX 等）
✅ MULTI/EXEC/DISCARD 队列 + 原子应用（WATCH 明确处置）
✅ 连接生命周期清理（断线退订 + 状态重置）
✅ RESP3 线上往返 + 事务回复形态矩阵全绿
✅ 全量回归 >=14140，存储/调度/事务/自治/合规路径零回退
✅ v3.5.0 RC 发布流水线（release.yml + release notes）
```

## 11. 后续方向（Phase 54+，不在本阶段范围）

- 事务增强：WATCH 全量版本守卫、跨段原子、事务持久化；
- 数据结构生产化：跳表索引、listpack/紧凑编码；
- 流式消费（Stream）、阻塞命令（BLPOP）、过期事件通知；
- SQL/向量原型转生产、分布式正确性验证、文档产品化。
