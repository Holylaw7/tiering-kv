# Phase 55 Task Prompt — Distributed Correctness, Consumer Groups & Product Docs

## 1. Context

Phase 54（v3.6.0 RC）完成事务加固（WATCH/EXEC）、Stream、阻塞命令、
过期通知与 SQL/向量生产化基础，命令注册表 109 个，全量回归
**14130/14130 全绿**。

完成度基线仍登记的产品级缺口（本阶段闭环）：

```text
1. 无线性一致性验证（Jepsen 式）：分布式正确性只有混沌/故障测试，
   缺少可复现的线性化历史验证；
2. Raft 边角未系统性验证：snapshot/install、pre-vote、成员变更、
   截断日志恢复、空心跳提交；
3. 滚动升级/备份恢复无端到端演练产物；
4. Stream 无消费组（XGROUP/XREADGROUP/XACK/XPENDING）；
5. 跨段 EXEC 事务无持久化日志（重启丢失审计）；
6. 文档为阶段流水账，非产品文档（无 quickstart/API 参考/运维手册）。
```

Phase 55 目标：**Distributed Correctness, Consumer Groups & Product
Docs（v3.7.0 RC）**——Jepsen 式线性一致性验证、Raft 边角矩阵、
升级/备份演练、Stream 消费组、跨段事务日志持久化、文档产品化，
并用验证/演练/持久化测试闭环。

当前基线：

```text
develop   : 3b720db merge: integrate Phase54 transaction hardening
            stream and production validation
定位      : Enterprise-ready Distributed Database（v3.6.0 RC）
Tests     : 14130/14130 PASS（另 6 项容器门控本地跳过）
Commands  : 109（CommandRegistry）
```

## 2. Release 前置项（Phase 25–54 遗留，本阶段保持终态）

| 编号 | 内容 | 终态 | Phase 55 处置 |
| --- | --- | --- | --- |
| TD-048/049、K8S-001 | 容器/块设备/kind 门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| REL-001 / TD-075 | 发布流水线记录 | REGISTERED_RELEASE | 待真实 tag |
| BM-001/002 等跨机项 | 跨机/跨地域门禁 | ENV_BLOCKED_FINAL | 保持封板 |
| TD-076 | 真实网络凭据 | ENV_BLOCKED_FINAL | 保持封板 |
| WATCH/EXEC 基础 | Phase 54 关闭 | 维持 | — |
| Stream 无消费组 | Phase 54 登记 | 本阶段关闭 | Goal 4 |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；WAL/RPC 冻结
  格式不变；新增持久化必须 additive；
- 线性一致性验证必须基于可复现历史（并发操作记录 + 线性化点），
  禁止只做冒烟；
- Raft 边角验证只测不改：发现缺陷必须走 ADR 修复流程；
- 消费组状态必须持久化（重启恢复），编码 additive；
- 跨段事务日志恢复必须在 EXEC 提交前落盘（崩溃一致性）；
- 文档产品化以第三方可独立上手为标准；
- 每阶段完成 `mvn test` 全量 0 failures（目标 ≥14730）。

## 3. Phase 55 Goal

目标：**Distributed Correctness, Consumer Groups & Product Docs
（v3.7.0 RC）**，完成 8 个 Goal：

1. 线性一致性验证（Jepsen 式历史 + 线性化点）
2. Raft 边角矩阵（snapshot/pre-vote/成员变更/截断恢复/空心跳）
3. 滚动升级与备份恢复演练
4. Stream 消费组（XGROUP/XREADGROUP/XACK/XPENDING）
5. 跨段事务日志持久化与崩溃恢复
6. 文档产品化（quickstart/API 参考/运维手册）
7. 最终性能/容量回归（真实口径白皮书）
8. v3.7.0 RC 冻结与发布流水线

## 4. Goals

### Goal 1 — 线性一致性验证

目标：可复现的 Jepsen 式线性一致性。

交付：

- `LinearizabilityChecker`：操作历史（invoke/response + 时间戳）+
  线性化点搜索（单键读写）；
- 并发写读矩阵：N 线程 × M 操作生成历史，验证可线性化；
- 违例检测：构造不可线性化历史必须被拒绝；
- 验收：线性化矩阵全绿 + 违例拒绝矩阵全绿。

ADR：`ADR-0297 Linearizability Verification`。

### Goal 2 — Raft 边角矩阵

目标：Raft 边角行为系统验证。

交付：

- snapshot 安装与重启重放、pre-vote 防脑裂、成员变更（add/remove）、
  截断日志恢复、空心跳不提交、滞后副本回填；
- 全部为验证测试（发现缺陷走 ADR 修复流程）；
- 验收：raft 边角矩阵全绿。

ADR：`ADR-0298 Raft Edge Case Validation`。

### Goal 3 — 滚动升级与备份恢复演练

目标：端到端演练产物。

交付：

- 滚动升级：逐节点升级 + 追平等待 + 数据奇偶校验；
- 备份/恢复：快照 + WAL + MVCC 索引闭环，恢复后校验；
- 演练脚本（scripts/upgrade-drill.sh / restore-drill.sh）+ 门控测试；
- 验收：演练矩阵全绿。

ADR：`ADR-0299 Upgrade & Backup Drills`。

### Goal 4 — Stream 消费组

目标：消费组基础能力。

交付：

- XGROUP CREATE/DESTROY；XREADGROUP GROUP g consumer COUNT n
  STREAMS key id；
- XACK key group id...；XPENDING key group；
- 组状态（last-delivered id + pending 集合）持久化（additive 编码）；
- 验收：消费组矩阵 + 确认矩阵 + 重启恢复矩阵全绿。

ADR：`ADR-0300 Stream Consumer Groups`。

### Goal 5 — 跨段事务日志持久化

目标：EXEC 崩溃一致性。

交付：

- ExecJournal 持久化（追加日志 + CRC，提交前落盘）；
- 恢复：未完成 EXEC 回滚登记 + 已完成 EXEC 审计保留；
- 与 WAL 冻结格式兼容（additive 文件，非 WAL 格式变更）；
- 验收：事务日志矩阵 + 崩溃恢复矩阵全绿。

ADR：`ADR-0301 Cross-Segment Transaction Persistence`。

### Goal 6 — 文档产品化

目标：第三方可独立上手。

交付：

- README 重写：5 分钟 quickstart（下载/启动/redis-cli 验证）；
- docs/operations/quickstart.md + operations-runbook.md；
- API 参考（命令表 + 回复形态 + 兼容矩阵链接）；
- 能力矩阵最终化（PRODUCT/EXPERIMENTAL/ADAPTER）；
- 验收：文档检查清单全绿（链接有效、命令可执行、无占位）。

ADR：`ADR-0302 Documentation Productization`。

### Goal 7 — 最终性能/容量回归

目标：真实口径白皮书。

交付：

- 全量 benchmark 汇总（LOCAL 口径，注明跨机封板）；
- 容量模型更新（CPU/内存/磁盘/网络）；
- docs/benchmark/final-performance-whitepaper.md；
- 验收：基准汇总矩阵全绿 + 口径如实。

ADR：`ADR-0302`。

### Goal 8 — v3.7.0 RC 冻结与发布流水线

目标：v3.7.0 RC。

交付：

- pom revision 3.7.0-SNAPSHOT；release.yml v3.7.0 标签 +
  Phase55BenchmarkTest/Baseline；
- `docs/release/v3.7.0-release-notes.md`；全量回归 ≥14730。

ADR：`ADR-0303 v3.7 Freeze & Release Pipeline`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0297 | Linearizability Verification |
| ADR-0298 | Raft Edge Case Validation |
| ADR-0299 | Upgrade & Backup Drills |
| ADR-0300 | Stream Consumer Groups |
| ADR-0301 | Cross-Segment Transaction Persistence |
| ADR-0302 | Documentation Productization |
| ADR-0303 | v3.7 Freeze & Release Pipeline |

## 6. Test Plan

新增目标：**>=600 tests**（Phase 55，surefire 口径）；

Phase 1-55 全量目标：**>=14730 tests**（当前 14130）。

| Module | Count |
| --- | ---: |
| 线性一致性验证 | 100 |
| Raft 边角矩阵 | 100 |
| 升级/备份演练 | 80 |
| Stream 消费组 | 100 |
| 事务日志持久化 | 80 |
| 文档检查清单 | 60 |
| 性能/容量回归 | 60 |
| 参数化边缘矩阵 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase55-distributed-correctness-review.md
docs/distributed/linearizability-verification.md
docs/distributed/raft-edge-cases.md
docs/operations/upgrade-backup-drills.md
docs/design/stream-consumer-groups.md
docs/transaction/cross-segment-txn-persistence.md
docs/operations/quickstart.md
docs/operations/operations-runbook.md
docs/benchmark/final-performance-whitepaper.md
docs/release/v3.7.0-release-notes.md
```

## 8. Engineering Rules

- 线性化验证可复现；Raft 边角只测不改（缺陷走 ADR）；
- 消费组状态与事务日志持久化 additive，崩溃一致；
- 文档以第三方可上手为标准；禁止占位；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase55-distributed-correctness-consumer-groups`

Commits：

```text
docs: add phase55 ADRs 0297-0303
feat(distributed): linearizability verification
test(distributed): raft edge case matrix
feat(operations): upgrade and backup restore drills
feat(storage): stream consumer groups
feat(transaction): persistent exec journal with crash recovery
docs: product documentation quickstart runbook api
perf(benchmark): final performance and capacity regression
feat(ci): v3.7 release and gate convergence
docs: phase55 release
```

Merge：`merge: integrate Phase55 distributed correctness consumer groups and product docs`

Checkpoint：`checkpoint-before-phase55` / `checkpoint-after-phase55`

## 10. Success Criteria

全部满足：

```text
✅ 线性一致性验证（可复现历史 + 线性化点 + 违例拒绝）
✅ Raft 边角矩阵（snapshot/pre-vote/成员变更/截断恢复/空心跳）
✅ 滚动升级与备份恢复演练（脚本 + 门控测试）
✅ Stream 消费组（XGROUP/XREADGROUP/XACK/XPENDING + 持久化）
✅ 跨段事务日志持久化（提交前落盘 + 崩溃恢复）
✅ 文档产品化（quickstart/API 参考/运维手册/能力矩阵）
✅ 最终性能/容量白皮书（真实口径）
✅ 全量回归 >=14730，存储/调度/事务/自治/合规路径零回退
✅ v3.7.0 RC 发布流水线（release.yml + release notes）
```

## 11. 后续方向（Phase 56+，不在本阶段范围）

- 正式发布（v3.7.0 GA）与真实 Runner 复审；
- Jepsen 外部化（独立进程驱动、网络分区注入）；
- 消费组高级能力（PEL 上限、重新声明、死信）；
- 多集群联邦一致性验证；
- 生产化收尾（SLO 报告、容量自动校准、发布归档）。
