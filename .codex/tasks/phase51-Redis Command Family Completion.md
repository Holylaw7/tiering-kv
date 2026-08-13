# Phase 51 Task Prompt — Redis Command Family Completion

## 1. Context

Phase 50（v3.2.0 GA）完成工程基座：版本模型对齐、结构化日志、质量
门禁、门禁终态 v16、JMH 基准、完成度基线。全量回归
**12666/12666 全绿**，行覆盖率 **92.32%**。

完成度基线明确指出产品主线的最大短板：**Redis 命令面过薄**。当前
`CommandRegistry` 只有 7 个命令（PING/ECHO/SET/GET/DEL/EXISTS/INFO），
存储层有 TTL 但命令层没有 EXPIRE/TTL；没有 INCR/APPEND/GETSET、
没有 MGET/MSET、没有 SCAN/DBSIZE/CONFIG。挂着"Redis 协议兼容"的
定位，命令面覆盖不到基础命令集的十分之一。

Phase 51 目标：**Redis Command Family Completion（v3.3.0 RC）**——
补齐字符串族 / TTL 族 / 多键族 / 管理族命令，建立 RESP2 兼容矩阵，
网关接入多键路由与 CROSSSLOT 校验，并用并发原子性测试证明命令语义
可信。本阶段只做命令面与协议层，不触碰存储内核。

当前基线：

```text
develop   : fa4cd65 merge: integrate Phase50 engineering foundation
            and v3.2 ga
定位      : Enterprise-ready Distributed Database（v3.2.0 GA）
Tests     : 12666/12666 PASS（另 6 项容器门控本地跳过）
Coverage  : line 92.32%（门禁阈值 70）
```

## 2. Release 前置项（Phase 25–50 遗留，本阶段保持终态）

| 编号 | 内容 | Phase 50 终态 | Phase 51 处置 |
| --- | --- | --- | --- |
| TD-048/049、K8S-001 | 容器/块设备/kind 门禁 | ENV_BLOCKED_FINAL | 保持封板，不滚动 defer |
| REL-001 / TD-075 | 发布流水线记录 | REGISTERED_RELEASE | 保持待真实 tag |
| BM-001/002、TD-051/054/059/060/063/078 | 跨机/跨地域门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| TD-076 | 真实网络凭据 | ENV_BLOCKED_FINAL | 保持封板 |
| TD-085~088 | 版本/日志/门禁/JMH | CLOSED | 维持 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机、存储内核；
- v1.0–v3.2 冻结协议不变，v3.3 扩展 additive（ADR-0103 兼容评审）；
- 命令必须走 CommandRegistry / StorageEngine SPI，禁止绕过存储层；
- TTL 语义必须复用现有 TTLManager 能力，禁止自造过期逻辑；
- SCAN 游标必须稳定、完整（快照语义），禁止漏键/重复承诺失真；
- CONFIG SET 仅允许白名单配置项；
- 兼容矩阵以 Redis 7.x 文档语义为准，差异必须如实记录；
- 每阶段完成 `mvn test` 全量 0 failures（目标 ≥13190）。

## 3. Phase 51 Goal

目标：**Redis Command Family Completion（v3.3.0 RC）**，完成 8 个 Goal：

1. 字符串命令族（INCR/DECR/APPEND/STRLEN/GETSET/SETNX 等）
2. TTL 命令族（EXPIRE/PEXPIRE/TTL/PTTL/PERSIST 等）
3. 多键命令族（MGET/MSET/MSETNX/DEL 多键/EXISTS 多键）
4. 管理命令族（DBSIZE/FLUSHDB/SCAN/TYPE/CONFIG/CLIENT/COMMAND）
5. RESP2 兼容矩阵（整数/nil/空串/错误消息/批量数组）
6. 网关命令路由与 CROSSSLOT 校验
7. 并发原子性与竞态测试（INCR lost-update / MSET 原子 / TTL 竞态）
8. v3.3.0 RC 冻结与发布流水线

## 4. Goals

### Goal 1 — 字符串命令族

目标：补齐字符串基础命令，原子且接入 WAL。

交付：

- `command/` 新命令：INCR / DECR / INCRBY / DECRBY / APPEND /
  STRLEN / GETSET / SETNX / SETEX / PSETEX / GETDEL / GETRANGE /
  SETRANGE；
- 数值语义：非整数报 WRONGTYPE / ERR value is not an integer；
- 原子性：通过存储引擎段锁内 read-modify-write 实现（单键）；
- 写命令接入 WAL 装饰器（additive，不改存储内核）；
- 验收：字符串命令矩阵 + 错误矩阵 + 原子性矩阵全绿。

ADR：`ADR-0269 String Command Family`。

### Goal 2 — TTL 命令族

目标：命令层补齐 TTL 全族，语义与 Redis 一致。

交付：

- EXPIRE / PEXPIRE / EXPIREAT / PEXPIREAT / TTL / PTTL / PERSIST；
- 单位换算与负值语义（EXPIRE key 0 = 立即删除）；
- 不存在 key 返回 0；无 TTL 返回 -1；key 存在但已过期返回 -2；
- 复用 TTLManager 能力（惰性 + 主动），不新增自造过期路径；
- 验收：TTL 命令矩阵 + 单位矩阵 + 边界矩阵全绿。

ADR：`ADR-0270 TTL Command Family`。

### Goal 3 — 多键命令族

目标：补齐多键批量命令与原子语义。

交付：

- MGET / MSET / MSETNX / DEL（多键）/ EXISTS（多键）；
- MSET 单命令原子性（全部成功或全部失败，单命令内无并发窗口）；
- DEL/EXISTS 多键返回值 = 受影响/存在键数量；
- 命令层批量透传 StorageEngine SPI（batch 能力复用）；
- 验收：批量矩阵 + 原子性矩阵 + 空输入矩阵全绿。

ADR：`ADR-0271 Multi-Key Batch Semantics`。

### Goal 4 — 管理命令族

目标：补齐运维/管理命令与服务器状态。

交付：

- DBSIZE / FLUSHDB / FLUSHALL / TYPE / SELECT（单库 no-op 兼容）；
- SCAN：游标式全量遍历（snapshot 语义，返回 next cursor + keys）；
- CONFIG GET / CONFIG SET（白名单，如 maxmemory/appendfsync）；
- CLIENT SETNAME / CLIENT GETNAME / COMMAND COUNT / COMMAND INFO；
- 验收：管理命令矩阵 + SCAN 游标矩阵 + 白名单矩阵全绿。

ADR：`ADR-0272 Admin & Scan Commands`。

### Goal 5 — RESP2 兼容矩阵

目标：以 Redis 7.x 语义为基准建立协议兼容矩阵。

交付：

- 整数回复（INCR/DEL/EXISTS/DB SIZE）；nil vs 空串（GET 缺失 =
  $-1，STRLEN 缺失 = 0）；
- 错误消息格式（-ERR ... / -WRONGTYPE ...），大小写与 Redis 对齐；
- 批量数组（MGET 缺失元素 = nil）；pipeline 下多命令保序；
- `ProtocolCompatibilityTest` 参数化矩阵 + docs/protocol/
  resp2-compatibility-matrix.md；
- 验收：兼容矩阵全绿；差异项如实登记。

ADR：`ADR-0273 RESP2 Compatibility Matrix`。

### Goal 6 — 网关命令路由与 CROSSSLOT 校验

目标：Redis Cluster 网关支持新命令与多键槽位校验。

交付：

- 网关命令路由扩展：字符串/TTL/管理命令透传；
- 多键命令（MGET/MSET/DEL/EXISTS）按 slot 分桶 + 同槽校验；
- 跨槽返回 `-CROSSSLOT Keys in request don't hash to the same
  slot`；MOVED/ASK 语义保持；
- 验收：网关路由矩阵 + CROSSSLOT 矩阵 + MOVED 矩阵全绿。

ADR：`ADR-0274 Gateway Command Routing & CROSSSLOT`。

### Goal 7 — 并发原子性与竞态测试

目标：证明命令语义在并发下可信。

交付：

- 100 线程同键 INCR：0 lost update，终值 = 线程数 × 次数；
- MSET 并发读：读方永远看到整组旧值或整组新值；
- TTL 竞态：expire 与 get 并发不返回已过期值；
- 压力口径（LOCAL）记录到命令延迟报告；
- 验收：并发矩阵全绿 + 延迟报告可复现。

ADR：`ADR-0269/0270`（语义已在命令族 ADR 覆盖，本 Goal 只加测试）。

### Goal 8 — v3.3.0 RC 冻结与发布流水线

目标：v3.3.0 RC。

交付：

- release.yml 扩展 v3.3.0 标签 + Phase51BenchmarkTest 接入；
- 旧客户端兼容矩阵（ADR-0103）继续执行；
- `docs/release/v3.3.0-release-notes.md`；
- 全量回归 ≥13190 全绿。

ADR：`ADR-0275 v3.3 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0269 | String Command Family |
| ADR-0270 | TTL Command Family |
| ADR-0271 | Multi-Key Batch Semantics |
| ADR-0272 | Admin & Scan Commands |
| ADR-0273 | RESP2 Compatibility Matrix |
| ADR-0274 | Gateway Command Routing & CROSSSLOT |
| ADR-0275 | v3.3 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=520 tests**（Phase 51，surefire 口径）；

Phase 1-51 全量目标：**>=13190 tests**（当前 12666）。

| Module | Count |
| --- | ---: |
| 字符串命令族 | 90 |
| TTL 命令族 | 80 |
| 多键命令族 | 70 |
| 管理/SCAN 命令 | 80 |
| RESP2 兼容矩阵 | 80 |
| 网关路由/CROSSSLOT | 50 |
| 并发原子性 | 40 |
| 参数化边缘矩阵 | 30 |

## 7. Documentation Deliverables

```text
docs/review/phase51-redis-command-family-review.md
docs/design/command-family-design.md
docs/protocol/resp2-compatibility-matrix.md
docs/operations/admin-and-scan-commands.md
docs/gateway/gateway-command-routing.md
docs/benchmark/phase51-production-report.md
docs/benchmark/command-latency-report.md
docs/release/v3.3.0-release-notes.md
docs/review/phase51-atomicity-concurrency-review.md
docs/operations/redis-cli-compatibility-guide.md
```

## 8. Engineering Rules

- 不修改存储内核 / Raft / MVCC / 事务状态机，全部 additive；
- 命令走 CommandRegistry + StorageEngine SPI；
- TTL 复用 TTLManager；SCAN 快照语义；CONFIG SET 白名单；
- 兼容语义以 Redis 7.x 文档为准，差异如实登记；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase51-redis-command-family`

Commits：

```text
docs: add phase51 ADRs 0269-0275
feat(command): string command family
feat(command): ttl command family
feat(command): multi-key batch commands
feat(command): admin and scan commands
test(protocol): resp2 compatibility matrix
feat(gateway): command routing and crossslot validation
feat(ci): v3.3 release and gate convergence
docs: phase51 release
```

Merge：`merge: integrate Phase51 redis command family completion`

Checkpoint：`checkpoint-before-phase51` / `checkpoint-after-phase51`

## 10. Success Criteria

全部满足：

```text
✅ 字符串命令族（INCR/DECR/APPEND/STRLEN/GETSET/SETNX 等）原子 + WAL
✅ TTL 命令族（EXPIRE/PEXPIRE/TTL/PTTL/PERSIST）语义与 Redis 一致
✅ 多键命令（MGET/MSET/MSETNX/DEL/EXISTS）批量 + 原子语义
✅ 管理命令（DBSIZE/FLUSHDB/SCAN/TYPE/CONFIG/CLIENT/COMMAND）
✅ RESP2 兼容矩阵（整数/nil/空串/错误/数组）全绿
✅ 网关命令路由 + CROSSSLOT 校验
✅ 并发原子性（INCR 0 lost update / MSET 原子 / TTL 竞态）
✅ 全量回归 >=13190，存储/调度/事务/自治/合规路径零回退
✅ v3.3.0 RC 发布流水线（release.yml + release notes）
```

## 11. 后续方向（Phase 52+，不在本阶段范围）

- 数据结构与协议演进：hash/list/set/zset、RESP3、Pub/Sub；
- 原型转生产：SQL 引擎、HNSW、控制台；
- 分布式正确性验证：Jepsen 式线性一致性、Raft 边角、升级/备份演练；
- 文档产品化：README 重写、API 参考、运维手册。
