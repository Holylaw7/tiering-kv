# Phase 54 Task Prompt — Transaction Hardening, Stream & Production Validation

## 1. Context

Phase 53（v3.5.0 RC）完成 RESP3 连接接线、Pub/Sub 连接投递与集群
广播 RPC、高级数据结构命令、MULTI/EXEC 事务队列，命令注册表 101 个，
全量回归 **13864/13864 全绿**。

Phase 53 评审登记的遗留（本阶段闭环）：

```text
1. WATCH 无版本守卫（乐观并发校验缺失）；
2. EXEC 非整体原子（跨段顺序执行，无回滚）；
3. 无 Stream 数据类型（XADD/XREAD/XLEN 等）；
4. 无阻塞命令（BLPOP/BRPOP 超时语义）；
5. 无过期事件通知（keyspace notifications）；
6. SQL/向量仍为原型：错误语义、持久化缺失。
```

Phase 54 目标：**Transaction Hardening, Stream & Production
Validation（v3.6.0 RC）**——WATCH 版本守卫 + EXEC 回滚/持久化，
Stream 数据类型与阻塞命令、过期事件通知，SQL/向量生产化基础，
并用事务/并发/持久化测试闭环。

当前基线：

```text
develop   : ed705c5 merge: integrate Phase53 resp3 wiring pubsub
            network and transactions
定位      : Enterprise-ready Distributed Database（v3.5.0 RC）
Tests     : 13864/13864 PASS（另 6 项容器门控本地跳过）
Commands  : 101（CommandRegistry）
```

## 2. Release 前置项（Phase 25–53 遗留，本阶段保持终态）

| 编号 | 内容 | 终态 | Phase 54 处置 |
| --- | --- | --- | --- |
| TD-048/049、K8S-001 | 容器/块设备/kind 门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| REL-001 / TD-075 | 发布流水线记录 | REGISTERED_RELEASE | 待真实 tag |
| BM-001/002 等跨机项 | 跨机/跨地域门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| TD-076 | 真实网络凭据 | ENV_BLOCKED_FINAL | 保持封板 |
| WATCH 无版本守卫 | Phase 53 登记 | 本阶段关闭 | Goal 1 |
| EXEC 非整体原子 | Phase 53 登记 | 本阶段收敛 | Goal 2 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机（MVCC 2PC 路径
  不变）；WAL/RPC 冻结格式不变；类型化编码 additive（STREAM 新标签）；
- WATCH 必须基于存储版本（key version），EXEC 前校验版本一致；
- EXEC 失败必须回滚或明确拒绝（禁止半程提交后静默）；
- 阻塞命令必须在事件循环外等待（不阻塞事件循环）；
- 过期通知经 PubSubBroker 发布（本地至少一次），不落盘；
- SQL/向量生产化只做语义/持久化收敛，不改存储内核；
- 每阶段完成 `mvn test` 全量 0 failures（目标 ≥14470）。

## 3. Phase 54 Goal

目标：**Transaction Hardening, Stream & Production Validation
（v3.6.0 RC）**，完成 8 个 Goal：

1. WATCH 版本守卫（key version + EXEC 校验）
2. EXEC 原子性与回滚（同段 applyBatch + 失败回滚 + 事务日志登记）
3. Stream 数据类型（XADD/XREAD/XLEN/XRANGE/XTRIM）
4. 阻塞命令（BLPOP/BRPOP/BLPOP timeout）
5. 过期事件通知（keyspace notifications）
6. SQL 生产化基础（错误语义 + EXPLAIN 完整化）
7. 向量持久化（HNSW 序列化 + 重建 + 混合检索接入）
8. v3.6.0 RC 冻结与发布流水线

## 4. Goals

### Goal 1 — WATCH 版本守卫

目标：乐观并发校验可用。

交付：

- 存储版本 API：`versionOf(key)`（AtomicStringOps 扩展，MemTable
  段读锁内返回 entry.version）；
- WATCH 记录被观察键版本到 ConnectionContext；
- EXEC 前校验全部被观察键版本一致，不一致返回 nil（abort）；
- UNWATCH 清空观察集；
- 验收：watch 矩阵 + 并发修改矩阵 + abort 矩阵全绿。

ADR：`ADR-0290 WATCH Version Guard`。

### Goal 2 — EXEC 原子性与回滚

目标：EXEC 收敛为可回滚/可登记。

交付：

- EXEC 先执行可写命令的预检（参数/类型校验）；
- 同段命令经 applyBatch 原子应用；失败回滚（记录回滚日志）；
- 事务执行结果登记到事务日志（TxnJournal 复用或轻量
  ExecJournal）；
- 跨段仍顺序执行，但失败必须整体回滚（快照恢复）或整体拒绝；
- 验收：exec 原子矩阵 + 回滚矩阵 + 日志矩阵全绿。

ADR：`ADR-0291 EXEC Atomicity & Rollback`。

### Goal 3 — Stream 数据类型

目标：Stream 基础能力。

交付：

- 新类型 STREAM（TypedValueCodec 标签 5）+ StreamCodec；
- XADD key id field value...（自增 id / 显式 id）；
- XREAD COUNT n STREAMS key id；XLEN；XRANGE key start end；
  XTRIM key MAXLEN n；
- 段锁原子更新；空 stream 保留；
- 验收：stream 命令矩阵 + id 矩阵 + trim 矩阵全绿。

ADR：`ADR-0292 Stream Data Type`。

### Goal 4 — 阻塞命令

目标：BLPOP/BRPOP 超时语义。

交付：

- BLPOP/BRPOP key... timeout：非阻塞等待（事件循环外）；
- 数据就绪或超时返回；0 = 无限等待；
- 内部条件队列（ListNotifier）避免轮询；
- 验收：阻塞矩阵 + 超时矩阵 + 多键矩阵全绿。

ADR：`ADR-0293 Blocking Commands`。

### Goal 5 — 过期事件通知

目标：keyspace notifications。

交付：

- TTLManager 过期钩子 → 发布
  `__keyspace@0__:<key>` expired 事件；
- 通知走 PubSubBroker（本地至少一次）；通知订阅开关可配置；
- 验收：通知矩阵 + 开关矩阵全绿。

ADR：`ADR-0294 Keyspace Expiry Notifications`。

### Goal 6 — SQL 生产化基础

目标：SQL 错误语义与 EXPLAIN 完整化。

交付：

- SqlEngine 错误矩阵：语法错误/未知列/类型错误统一错误码；
- EXPLAIN 输出完整计划树（scan/join/aggregate/pushdown）；
- 谓词下推与结果缓存一致性校验；
- 验收：sql 错误矩阵 + explain 矩阵全绿。

ADR：`ADR-0295 SQL/Vector Production Hardening`。

### Goal 7 — 向量持久化

目标：HNSW 可持久化、可重建。

交付：

- HnswIndex 序列化/反序列化（图 + 向量 + 参数）；
- 重启重建与一致性校验；混合检索（HNSW + 标量过滤）接入存储；
- 验收：向量持久化矩阵 + 重建矩阵 + 混合检索矩阵全绿。

ADR：`ADR-0295`。

### Goal 8 — v3.6.0 RC 冻结与发布流水线

目标：v3.6.0 RC。

交付：

- pom revision 3.6.0-SNAPSHOT；release.yml v3.6.0 标签 +
  Phase54BenchmarkTest/Baseline；
- `docs/release/v3.6.0-release-notes.md`；全量回归 ≥14470。

ADR：`ADR-0296 v3.6 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0290 | WATCH Version Guard |
| ADR-0291 | EXEC Atomicity & Rollback |
| ADR-0292 | Stream Data Type |
| ADR-0293 | Blocking Commands |
| ADR-0294 | Keyspace Expiry Notifications |
| ADR-0295 | SQL/Vector Production Hardening |
| ADR-0296 | v3.6 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=600 tests**（Phase 54，surefire 口径）；

Phase 1-54 全量目标：**>=14470 tests**（当前 13864）。

| Module | Count |
| --- | ---: |
| WATCH 版本守卫 | 90 |
| EXEC 原子/回滚/日志 | 90 |
| Stream 数据类型 | 100 |
| 阻塞命令 | 80 |
| 过期通知 | 70 |
| SQL 生产化 | 80 |
| 向量持久化 | 70 |
| 参数化边缘矩阵 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase54-txn-stream-production-review.md
docs/transaction/watch-version-guard.md
docs/transaction/exec-atomicity-rollback.md
docs/design/stream-data-type.md
docs/operations/blocking-commands.md
docs/operations/keyspace-notifications.md
docs/sql/sql-production-hardening.md
docs/vector/vector-persistence.md
docs/benchmark/phase54-production-report.md
docs/release/v3.6.0-release-notes.md
```

## 8. Engineering Rules

- WATCH 基于存储版本；EXEC 失败回滚或整体拒绝；
- 阻塞命令事件循环外等待；
- STREAM 标签 additive（冻结格式不变）；
- 过期通知本地至少一次不落盘；
- SQL/向量不改存储内核；使用 Conventional Commits；
- 每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase54-txn-hardening-stream-production`

Commits：

```text
docs: add phase54 ADRs 0290-0296
feat(transaction): watch version guard
feat(transaction): exec atomicity rollback and journal
feat(storage): stream data type and commands
feat(operations): blocking list commands
feat(operations): keyspace expiry notifications
feat(sql): sql production hardening and explain
feat(vector): hnsw persistence and hybrid retrieval
feat(ci): v3.6 release and gate convergence
docs: phase54 release
```

Merge：`merge: integrate Phase54 transaction hardening stream and production validation`

Checkpoint：`checkpoint-before-phase54` / `checkpoint-after-phase54`

## 10. Success Criteria

全部满足：

```text
✅ WATCH 版本守卫（版本一致 EXEC 执行，不一致 abort）
✅ EXEC 回滚/整体拒绝 + 事务日志登记
✅ Stream 数据类型（XADD/XREAD/XLEN/XRANGE/XTRIM）
✅ 阻塞命令（BLPOP/BRPOP 超时语义，事件循环外等待）
✅ 过期事件通知（keyspace 事件 + 开关）
✅ SQL 错误语义 + EXPLAIN 完整计划树
✅ HNSW 持久化/重建 + 混合检索
✅ 全量回归 >=14470，存储/调度/事务/自治/合规路径零回退
✅ v3.6.0 RC 发布流水线（release.yml + release notes）
```

## 11. 后续方向（Phase 55+，不在本阶段范围）

- 分布式正确性验证（Jepsen 式线性一致性、Raft 边角、升级/备份演练）；
- 事务增强：跨段原子事务持久化、保存点、级联回滚；
- Stream 消费组（XGROUP/XREADGROUP）、消费者确认；
- 阻塞命令集群路由、过期通知集群广播；
- 文档产品化与正式发布。
