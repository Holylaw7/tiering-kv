# Phase 52 Task Prompt — Data Structures, RESP3 & Pub/Sub

## 1. Context

Phase 51（v3.3.0 RC）把命令面从 7 个扩展到 38 个：字符串/TTL/多键/
管理命令族全部补齐，RESP2 兼容矩阵、网关 CROSSSLOT、并发原子性
闭环。全量回归 **13141/13141 全绿**。

完成度基线仍标注的产品主线缺口：

```text
1. 只有 string 类型：无 hash / list / set / zset；
2. 只有 RESP2：无 RESP3（Map/Set/Double/Push 等类型）；
3. 无 Pub/Sub：无法支撑实时消息与广播场景；
4. TYPE 恒返回 string，数据结构命令全部缺失。
```

Phase 52 目标：**Data Structures, RESP3 & Pub/Sub（v3.4.0 RC）**——
补齐四类数据结构命令族与类型化存储编码，RESP3 作为 additive 协议
演进（HELLO 3 切换，默认仍 RESP2），落地本地 Pub/Sub 与集群广播
接口，并以数据一致性/并发测试证明语义可信。

当前基线：

```text
develop   : a8c5379 merge: integrate Phase51 redis command family
            completion
定位      : Enterprise-ready Distributed Database（v3.3.0 RC）
Tests     : 13141/13141 PASS（另 6 项容器门控本地跳过）
Commands  : 38（CommandRegistry）
```

## 2. Release 前置项（Phase 25–51 遗留，本阶段保持终态）

| 编号 | 内容 | 终态 | Phase 52 处置 |
| --- | --- | --- | --- |
| TD-048/049、K8S-001 | 容器/块设备/kind 门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| REL-001 / TD-075 | 发布流水线记录 | REGISTERED_RELEASE | 待真实 tag |
| BM-001/002 等跨机项 | 跨机/跨地域门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| TD-076 | 真实网络凭据 | ENV_BLOCKED_FINAL | 保持封板 |
| CLIENT 无会话态 | Phase 51 登记 | ACCEPTED_LIMITATION | 保持登记 |
| 跨段 MSET 原子性 | Phase 51 登记 | ACCEPTED_LIMITATION | 同槽约束维持 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机、WAL/RPC 冻结
  格式；类型化编码必须 additive（value 字节携带类型标签，格式不变）；
- RESP3 必须 additive：默认 RESP2，`HELLO 3` 才切换；RESP2 客户端
  零影响；
- 数据结构命令必须复用 StorageEngine 原子操作模式（段锁内
  read-modify-write），禁止命令层无锁 get+put；
- TTL 对类型化键整体生效（键级 TTL，不拆字段级）；
- Pub/Sub 为本地至少一次投递，不落盘、不跨进程保证（集群广播
  接口预留）；禁止改动事务/Raft 路径；
- 每阶段完成 `mvn test` 全量 0 failures（目标 ≥13700）。

## 3. Phase 52 Goal

目标：**Data Structures, RESP3 & Pub/Sub（v3.4.0 RC）**，完成
8 个 Goal：

1. 类型化值编码与存储模型（Hash/List/Set/ZSet 序列化 + 类型标签）
2. Hash 命令族（HSET/HGET/HDEL/HMGET/HGETALL 等）
3. List 命令族（LPUSH/RPUSH/LPOP/RPOP/LRANGE 等）
4. Set 命令族（SADD/SREM/SISMEMBER/SINTER 等）
5. ZSet 命令族（ZADD/ZSCORE/ZRANGE/ZINCRBY 等）
6. RESP3 协议演进（Map/Set/Double/Push/HELLO 3）
7. Pub/Sub 消息（SUBSCRIBE/PUBLISH/PSUBSCRIBE + 集群广播接口）
8. v3.4.0 RC 冻结与发布流水线

## 4. Goals

### Goal 1 — 类型化值编码与存储模型

目标：string 之外的四种数据结构可持久化、可 TTL、可迁移。

交付：

- `TypedValue`：type tag（STRING/HASH/LIST/SET/ZSET）+ payload
  （自定义紧凑二进制编码，CRC 可选）；
- `storage/memory/` 类型化编码器/解码器 + 序列化矩阵测试；
- MemTable 段锁内按类型 read-modify-write（与 AtomicStringOps
  同一模式）；TTL 键级生效；
- WAL：以 value 字节整体落盘（冻结格式不变，恢复按类型标签解码）；
- 兼容：GET/TYPE/STRLEN 对非 string 返回 WRONGTYPE。

ADR：`ADR-0276 Typed Value Encoding & Storage Model`。

### Goal 2 — Hash 命令族

目标：Hash 基础命令完整。

交付：

- HSET / HGET / HDEL / HEXISTS / HLEN / HKEYS / HVALS / HGETALL /
  HMGET / HMSET / HINCRBY / HSETNX；
- 字段序与 Redis 一致（HKEYS/HVALS 插入序）；
- HINCRBY 段锁内原子；WRONGTYPE 对非 hash；
- 验收：hash 命令矩阵 + 类型矩阵 + 并发矩阵全绿。

ADR：`ADR-0277 Hash Command Family`。

### Goal 3 — List 命令族

目标：List 基础命令完整。

交付：

- LPUSH / RPUSH / LPOP / RPOP / LLEN / LRANGE / LINDEX / LSET /
  LREM / LTRIM；
- 头尾操作 O(1)（索引结构）；负数索引语义与 Redis 一致；
- WRONGTYPE 对非 list；空 list 自动删除（键删除语义）；
- 验收：list 命令矩阵 + 索引矩阵 + 并发矩阵全绿。

ADR：`ADR-0278 List Command Family`。

### Goal 4 — Set 命令族

目标：Set 基础命令与集合运算。

交付：

- SADD / SREM / SISMEMBER / SCARD / SMEMBERS / SPOP / SRANDMEMBER /
  SINTER / SUNION / SDIFF / SINTERSTORE / SUNIONSTORE / SDIFFSTORE；
- 元素唯一、无序（SMEMBERS 顺序稳定但语义无序）；
- 集合运算结果新建键（STORE 变体）；
- 验收：set 命令矩阵 + 运算矩阵 + 类型矩阵全绿。

ADR：`ADR-0279 Set Command Family`。

### Goal 5 — ZSet 命令族

目标：有序集合基础命令与分数语义。

交付：

- ZADD / ZSCORE / ZRANGE / ZREVRANGE / ZREM / ZCARD / ZINCRBY /
  ZRANGEBYSCORE / ZCOUNT / ZRANK / ZREVRANK；
- 分数 double，NaN 拒绝；同分按字典序；
- ZINCRBY 段锁内原子；
- 验收：zset 命令矩阵 + 分数矩阵 + 排序矩阵全绿。

ADR：`ADR-0280 ZSet Command Family`。

### Goal 6 — RESP3 协议演进

目标：RESP3 作为 additive 演进，RESP2 零影响。

交付：

- 新类型：Map（%）、Set（~）、Double（,）、BigNumber（(）、Push（>）；
- HELLO 3：切换协议版本并返回 server 信息；HELLO 2 回退；
- 协议版本随连接状态（ConnectionProtocolState），默认 RESP2；
- RESP2 客户端发送 HELLO 3 后按 RESP3 编码回复；
- 验收：RESP3 编码矩阵 + HELLO 矩阵 + RESP2 兼容矩阵全绿。

ADR：`ADR-0281 RESP3 Protocol Evolution`。

### Goal 7 — Pub/Sub 消息

目标：本地 Pub/Sub 与集群广播接口。

交付：

- SUBSCRIBE / UNSUBSCRIBE / PUBLISH / PSUBSCRIBE /
  PUNSUBSCRIBE；
- 本地 broker（channel → subscribers，pattern 匹配）；
- 消息投递为本地至少一次；连接断开订阅清理；
- 集群广播接口（`PubSubForwarder` SPI）预留，网络实现 Phase 53+；
- 验收：pubsub 命令矩阵 + 模式矩阵 + 并发矩阵全绿。

ADR：`ADR-0282 Pub/Sub Messaging & v3.4 Freeze`。

### Goal 8 — v3.4.0 RC 冻结与发布流水线

目标：v3.4.0 RC。

交付：

- pom revision 3.4.0-SNAPSHOT；release.yml v3.4.0 标签 +
  Phase52BenchmarkTest/Baseline；
- `docs/release/v3.4.0-release-notes.md`；旧客户端兼容矩阵
  （ADR-0103）继续执行；
- 全量回归 ≥13700 全绿。

ADR：`ADR-0282`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0276 | Typed Value Encoding & Storage Model |
| ADR-0277 | Hash Command Family |
| ADR-0278 | List Command Family |
| ADR-0279 | Set Command Family |
| ADR-0280 | ZSet Command Family |
| ADR-0281 | RESP3 Protocol Evolution |
| ADR-0282 | Pub/Sub Messaging & v3.4 Freeze |

## 6. Test Plan

新增目标：**>=560 tests**（Phase 52，surefire 口径）；

Phase 1-52 全量目标：**>=13700 tests**（当前 13141）。

| Module | Count |
| --- | ---: |
| 类型化编码/存储 | 70 |
| Hash 命令族 | 80 |
| List 命令族 | 80 |
| Set 命令族 | 80 |
| ZSet 命令族 | 90 |
| RESP3 协议 | 80 |
| Pub/Sub | 60 |
| 参数化边缘矩阵 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase52-data-structures-protocol-review.md
docs/design/data-structure-encoding.md
docs/protocol/resp3-support.md
docs/design/hash-list-set-zset-commands.md
docs/operations/pubsub-guide.md
docs/benchmark/phase52-production-report.md
docs/benchmark/data-structure-latency-report.md
docs/release/v3.4.0-release-notes.md
docs/review/phase52-pubsub-review.md
docs/operations/redis-cli-data-structures-guide.md
```

## 8. Engineering Rules

- WAL/RPC 冻结格式不变，类型化编码 additive（value 字节携带类型
  标签）；
- RESP3 additive，默认 RESP2，HELLO 3 才切换；
- 数据结构命令段锁内 read-modify-write，禁止命令层无锁 get+put；
- TTL 键级生效；WRONGTYPE 语义对齐 Redis 7.x；
- Pub/Sub 本地至少一次，不落盘；集群广播接口 SPI 预留；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase52-data-structures-resp3-pubsub`

Commits：

```text
docs: add phase52 ADRs 0276-0282
feat(storage): typed value encoding and storage model
feat(command): hash command family
feat(command): list command family
feat(command): set command family
feat(command): zset command family
feat(protocol): resp3 protocol evolution
feat(operations): pubsub messaging and cluster forwarder spi
feat(ci): v3.4 release and gate convergence
docs: phase52 release
```

Merge：`merge: integrate Phase52 data structures resp3 and pubsub`

Checkpoint：`checkpoint-before-phase52` / `checkpoint-after-phase52`

## 10. Success Criteria

全部满足：

```text
✅ 类型化值编码（HASH/LIST/SET/ZSET 标签 + 序列化 + TTL 键级）
✅ Hash 命令族全绿（含 HINCRBY 原子）
✅ List 命令族全绿（含头尾 O(1) 与负数索引）
✅ Set 命令族全绿（含 SINTER/SUNION/SDIFF 与 STORE 变体）
✅ ZSet 命令族全绿（分数/排序/原子 ZINCRBY）
✅ RESP3（Map/Set/Double/Push/HELLO 3）additive 且 RESP2 零影响
✅ Pub/Sub 本地至少一次 + 模式订阅 + 集群广播 SPI
✅ 全量回归 >=13700，存储/调度/事务/自治/合规路径零回退
✅ v3.4.0 RC 发布流水线（release.yml + release notes）
```

## 11. 后续方向（Phase 53+，不在本阶段范围）

- Pub/Sub 集群网络化（跨节点广播、消息确认）；
- 数据结构高级命令（HSCAN/ZRANGEBYLEX/LINSERT/LMOVE 等）；
- 事务与数据结构联动（MULTI/EXEC、WATCH）；
- 原型转生产（SQL/向量）、分布式正确性验证、文档产品化。
