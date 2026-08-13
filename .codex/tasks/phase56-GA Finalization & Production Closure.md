# Phase 56 Task Prompt — GA Finalization & Production Closure

## 1. Context

Phase 55（v3.7.0 RC）完成分布式正确性验证、消费组、事务日志持久化与
文档产品化，命令注册表 113 个，全量回归 **14378/14378 全绿**。

项目已覆盖：

```text
Phase 1-18 : Storage / Raft / Multi-Raft / Region / Migration / Gateway
Phase 19-23: MVCC / 2PC / 事务 RPC / 生命周期 / 运行时
Phase 24-26: 云原生 v1 / 控制面 GA / v1 冻结
Phase 27-35: 跨地域 / SQL / 向量 / SaaS / 自治 / 合规
Phase 36-45: 门禁收敛 v2-v11 / 真实 Runner 渐进闭环
Phase 46-55: 门禁 v12-v15 / 多组织联邦 / 工程基座 / 命令面 /
             数据结构 / RESP3 / 事务 / Stream / 正确性验证
```

Phase 56 目标：**GA Finalization & Production Closure（v3.7.0 GA）**——
执行 GA 冻结与发布、对长期封板的真实 Runner 门禁做最终复审决策、
Jepsen 式验证外部化、消费组高级能力、多集群联邦一致性验证、运营
收尾与最终质量门禁，输出 GA 完成度基线。

当前基线：

```text
develop   : 607e224 merge: integrate Phase55 distributed correctness
            consumer groups and product docs
定位      : Enterprise-ready Distributed Database（v3.7.0 RC）
Tests     : 14378/14378 PASS（另 6 项容器门控本地跳过）
Commands  : 113（CommandRegistry）
```

## 2. Release 前置项（Phase 25–55 遗留，本阶段最终决策）

| 编号 | 内容 | 当前终态 | Phase 56 决策 |
| --- | --- | --- | --- |
| TD-048/049、K8S-001 | 容器/块设备/kind 门禁 | ENV_BLOCKED_FINAL | 无远程 Runner → 正式封板声明（GA 已知限制） |
| REL-001 / TD-075 | 发布流水线记录 | REGISTERED_RELEASE | 发布就绪；无远程 → 就绪声明 + 待触发记录 |
| BM-001/002 等跨机项 | 跨机/跨地域门禁 | ENV_BLOCKED_FINAL | 正式封板（交付物 + 阻塞原因归档） |
| TD-076 | 真实网络凭据 | ENV_BLOCKED_FINAL | 正式封板（探测矩阵已 JVM 闭环） |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；冻结协议不变；
- GA 发布必须：全量回归 0 failures + 版本一致 + release notes 定稿 +
  门禁终态唯一；
- 真实 Runner 项禁止伪报：无远程则正式封板并归档，不滚动 defer；
- Jepsen 外部化必须可独立进程运行（harness 可复现）；
- 消费组高级能力持久化 additive；
- 多集群联邦一致性验证基于既有复制/CRDT 能力，不改内核；
- 每阶段完成 `mvn test` 全量 0 failures（目标 ≥14880）。

## 3. Phase 56 Goal

目标：**GA Finalization & Production Closure（v3.7.0 GA）**，完成
8 个 Goal：

1. v3.7.0 GA 冻结与发布执行
2. 真实 Runner 门禁最终复审与封板声明
3. Jepsen 式验证外部化（独立 harness）
4. 消费组高级能力（PEL 上限/重新声明/死信）
5. 多集群联邦一致性验证
6. 运营收尾（SLO 报告/归档/审计导出）
7. 最终质量门禁（回归/覆盖/静态/文档/基准）
8. GA 完成度基线（能力/技术债终态/成品判定）

## 4. Goals

### Goal 1 — v3.7.0 GA 冻结与发布执行

目标：GA 发布就绪。

交付：

- release.yml v3.7.0 标签 + Phase56BenchmarkTest/Baseline；
- `docs/release/v3.7.0-ga-release-notes.md` 定稿；
- 版本一致性校验（pom/tag/notes/changelog）全绿；
- 发布就绪声明（无远程时如实登记）。

ADR：`ADR-0304 v3.7 GA Freeze & Release Execution`。

### Goal 2 — 真实 Runner 门禁最终复审与封板声明

目标：门禁终态唯一且归档。

交付：

- `GateConvergenceV17`：GA 终态（CLOSED / SEALED_GA /
  REGISTERED_RELEASE），无滚动 defer；
- 封板声明文档（docs/deployment/real-runner-final-review.md）：
  交付物、阻塞原因、复审条件；
- 复审决策矩阵测试全绿。

ADR：`ADR-0305 Real Runner Final Review & Gate Sealing`。

### Goal 3 — Jepsen 式验证外部化

目标：可独立进程运行的验证 harness。

交付：

- `distributed/harness/`：历史生成器 + 并发客户端 + 结果校验器；
- CLI 入口（可独立运行，输出验证报告）；
- 与 LinearizabilityChecker 接线；网络分区注入接口预留；
- 验收：harness 矩阵 + 可复现报告矩阵全绿。

ADR：`ADR-0306 Jepsen-style Harness Externalization`。

### Goal 4 — 消费组高级能力

目标：PEL 管理与死信。

交付：

- PEL 上限（XGROUP SETID/MAXPENDING？以 XAUTOCLAIM 为主）；
- XAUTOCLAIM：重新声明过期 pending；
- XCLAIM：显式重新声明；死信计数（MAXDELIVERIES 登记）；
- 持久化 additive；验收：高级矩阵全绿。

ADR：`ADR-0307 Consumer Group Advanced Capabilities`。

### Goal 5 — 多集群联邦一致性验证

目标：跨集群一致性矩阵。

交付：

- 双活冲突率/收敛时间矩阵（复用 VersionVector/CRDT）；
- 跨集群复制链路校验（环回抑制 + 冲突合并）；
- 验收：联邦一致性矩阵全绿。

ADR：`ADR-0308 Multi-Cluster Federation Consistency`。

### Goal 6 — 运营收尾

目标：GA 运营资产。

交付：

- SLO 报告（本地基线 + 跨地域封板声明）；
- 发布归档（docs/release/archive/）；
- 审计导出（合规/事务/门禁）；
- 验收：运营矩阵全绿。

ADR：`ADR-0309 Operations Closure & GA Baseline`。

### Goal 7 — 最终质量门禁

目标：GA 质量封板。

交付：

- 全量回归 0 failures；覆盖率/静态分析报告；
- 文档检查清单；最终基准汇总定稿；
- 验收：门禁矩阵全绿。

ADR：`ADR-0310 GA Final Quality Gates`。

### Goal 8 — GA 完成度基线

目标：成品判定。

交付：

- `ProductCompletenessBaseline` v2：能力终态 + 技术债终态 +
  成品判定清单；
- 判定：GA 发布就绪（含封板声明）；
- 验收：基线矩阵全绿。

ADR：`ADR-0309`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0304 | v3.7 GA Freeze & Release Execution |
| ADR-0305 | Real Runner Final Review & Gate Sealing |
| ADR-0306 | Jepsen-style Harness Externalization |
| ADR-0307 | Consumer Group Advanced Capabilities |
| ADR-0308 | Multi-Cluster Federation Consistency |
| ADR-0309 | Operations Closure & GA Baseline |
| ADR-0310 | GA Final Quality Gates |

## 6. Test Plan

新增目标：**>=500 tests**（Phase 56，surefire 口径）；

Phase 1-56 全量目标：**>=14880 tests**（当前 14378）。

| Module | Count |
| --- | ---: |
| GA 发布/门禁终态 | 70 |
| Jepsen harness | 90 |
| 消费组高级 | 100 |
| 多集群联邦一致性 | 90 |
| 运营收尾/归档 | 60 |
| 最终质量门禁 | 60 |
| GA 基线 | 30 |
| 参数化边缘矩阵 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase56-ga-finalization-review.md
docs/release/v3.7.0-ga-release-notes.md
docs/deployment/real-runner-final-review.md
docs/distributed/jepsen-harness.md
docs/design/consumer-group-advanced.md
docs/distributed/multi-cluster-federation.md
docs/operations/ga-operations-closure.md
docs/benchmark/ga-final-benchmark-summary.md
docs/review/product-completeness-baseline-v2.md
docs/release/archive/ga-release-archive.md
```

## 8. Engineering Rules

- GA 发布不伪报：无远程则封板声明 + 归档；
- Jepsen harness 独立可运行、可复现；
- 消费组高级能力 additive 持久化；
- 多集群验证复用既有 CRDT/复制，不改内核；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase56-ga-finalization`

Commits：

```text
docs: add phase56 ADRs 0304-0310
feat(ci): v3.7 ga freeze and release execution
feat(gates): real runner final review and gate sealing v17
feat(distributed): jepsen style harness externalization
feat(storage): consumer group advanced capabilities
feat(distributed): multi cluster federation consistency
feat(operations): ga operations closure and archive
perf(benchmark): ga final quality gates and baseline
docs: phase56 ga release
```

Merge：`merge: integrate Phase56 ga finalization and production closure`

Checkpoint：`checkpoint-before-phase56` / `checkpoint-after-phase56`

## 10. Success Criteria

全部满足：

```text
✅ v3.7.0 GA 冻结与发布就绪（版本一致 + notes 定稿 + 门禁唯一终态）
✅ 真实 Runner 门禁最终复审（封板声明 + 归档，无滚动 defer）
✅ Jepsen 式 harness 独立可运行（历史 + 并发 + 报告）
✅ 消费组高级能力（XAUTOCLAIM/XCLAIM/死信登记）
✅ 多集群联邦一致性矩阵（冲突率/收敛时间）
✅ 运营收尾（SLO/归档/审计导出）
✅ 最终质量门禁（回归/覆盖/静态/文档/基准）
✅ GA 完成度基线（能力/技术债/成品判定）
✅ 全量回归 >=14880，存储/调度/事务/自治/合规路径零回退
✅ v3.7.0 GA 发布流水线（release.yml + release notes）
```

## 11. 后续方向（Phase 57+，不在本阶段范围）

- 维护模式（fix-only）与 v4.0 规划；
- 真实 Runner 可用后的复审执行（门禁 SEALED_GA → CLOSED）；
- Jepsen 网络分区外部化执行；
- 多集群联邦跨地域基准；
- 文档/基准年度复核。
