# Phase 57 Task Prompt — Maintenance Mode & v4 Planning

## 1. Context

Phase 56（v3.7.0 GA）完成发布冻结、门禁封板、Jepsen harness、消费组
高级能力、联邦一致性、运营收尾与 GA 基线。命令注册表 115 个，全量
回归 **14514/14514 全绿**。

项目进入维护模式（fix-only）：

```text
已交付：v3.7.0 GA（命令面 115、分布式正确性、事务、Stream、文档）
待复审：SEALED_GA 门禁（真实 Runner 可用后执行）
待规划：v4.0（多模型 / 多集群 / 云原生深化）
```

Phase 57 目标：**Maintenance Mode & v4 Planning**——建立维护模式
框架（hotfix 流程 / 补丁发布 / backport 策略）、真实 Runner 复审执行
包、v4.0 规划框架、年度复核机制、维护质量门禁、发布卫生与社区交付
就绪，并输出维护基线。

当前基线：

```text
develop   : d93d28d merge: integrate Phase56 ga finalization and
            production closure
定位      : Enterprise-ready Distributed Database（v3.7.0 GA，
            maintenance mode）
Tests     : 14514/14514 PASS（另 6 项容器门控本地跳过）
Commands  : 115（CommandRegistry）
```

## 2. Release 前置项（GA 遗留，本阶段维护化）

| 编号 | 内容 | GA 终态 | Phase 57 处置 |
| --- | --- | --- | --- |
| TD-048/049、K8S-001 | 容器/块设备/kind 门禁 | SEALED_GA | 复审执行包就绪（流程/脚本/证据模板） |
| REL-001 / TD-075 | 发布流水线记录 | REGISTERED_RELEASE | 补丁发布流水线 v3.7.1 就绪 |
| BM-001/002、TD-076 | 跨机/凭据门禁 | SEALED_GA | 复审执行包覆盖 |

原则（禁止变更）：

- 维护模式 fix-only：禁止新功能进 develop（v4.0 特性走规划/分支）；
- 不修改 Raft safety、MVCC consistency、事务状态机；冻结协议不变；
- 修复必须：测试先行 + 全量回归 0 failures + Conventional Commit；
- hotfix 走 fix/* 分支 → develop → main；backport 策略文档化；
- 复审执行包必须可执行、可留证、SEALED_GA → CLOSED 流程明确；
- 每阶段完成 `mvn test` 全量 0 failures（目标 ≥14820）。

## 3. Phase 57 Goal

目标：**Maintenance Mode & v4 Planning**，完成 8 个 Goal：

1. 维护模式框架（hotfix 流程 + backport 策略 + 补丁流水线）
2. 真实 Runner 复审执行包（流程/脚本/证据模板）
3. v4.0 规划框架（路线图/RFC/ADR 预研清单）
4. 年度复核机制（文档/基准/能力矩阵）
5. 维护质量门禁（回归/覆盖/静态/依赖漏洞）
6. 发布卫生（版本策略/SBOM/签名/归档）
7. 社区与交付就绪（CONTRIBUTING/issue/安全披露）
8. 维护基线（v3.7.1-rc 就绪声明 + 维护报告）

## 4. Goals

### Goal 1 — 维护模式框架

目标：fix-only 流程可执行。

交付：

- `docs/operations/maintenance-mode.md`：hotfix 流程
  （fix/* → develop → main）、backport 策略、补丁版本规则；
- `scripts/hotfix.sh`：修复分支创建 + 校验 + 提交模板；
- release.yml 补丁标签支持（v3.7.1-rc*/v3.7.1）；
- 验收：维护流程矩阵全绿。

ADR：`ADR-0311 Maintenance Mode & Hotfix Flow`。

### Goal 2 — 真实 Runner 复审执行包

目标：SEALED_GA → CLOSED 可执行。

交付：

- `docs/deployment/runner-review-execution-pack.md`：执行清单 +
  脚本引用 + 证据模板；
- `scripts/runner-review.sh`：门禁逐项执行 + 证据归档；
- 复审决策矩阵测试全绿。

ADR：`ADR-0312 Real Runner Review Execution Pack`。

### Goal 3 — v4.0 规划框架

目标：v4.0 方向可评审。

交付：

- `docs/planning/v4-roadmap.md`：多模型/多集群/云原生深化路线；
- RFC 模板（docs/planning/rfc-template.md）+ ADR 预研清单；
- 规划评审矩阵测试全绿。

ADR：`ADR-0313 v4.0 Planning Framework`。

### Goal 4 — 年度复核机制

目标：文档/基准/能力矩阵可复核。

交付：

- `docs/operations/annual-review.md`：年度检查清单（文档/基准/
  能力矩阵/门禁）；
- `scripts/annual-review.sh`：检查执行 + 报告生成；
- 复核矩阵测试全绿。

ADR：`ADR-0314 Annual Review & Capability Rebaseline`。

### Goal 5 — 维护质量门禁

目标：维护期质量不降级。

交付：

- 维护门禁脚本（回归 + 覆盖率 + 静态 + 依赖漏洞）；
- 门禁矩阵测试全绿。

ADR：`ADR-0315 Maintenance Quality Gates`。

### Goal 6 — 发布卫生

目标：版本与制品可审计。

交付：

- 版本策略（semver + GA/patch/rc 规范）文档；
- SBOM/签名/归档策略（scripts/sbom.sh）；
- 发布卫生矩阵测试全绿。

ADR：`ADR-0316 Release Hygiene & Artifacts`。

### Goal 7 — 社区与交付就绪

目标：第三方可参与。

交付：

- CONTRIBUTING 维护更新；issue 模板；
- 安全披露流程（docs/security/disclosure-policy.md）；
- 交付就绪矩阵测试全绿。

ADR：`ADR-0317 Community & Delivery Readiness`。

### Goal 8 — 维护基线

目标：v3.7.1-rc 就绪声明。

交付：

- `docs/release/v3.7.1-rc-maintenance-notes.md`；
- 维护报告（docs/review/phase57-maintenance-review.md）；
- 全量回归 ≥14820。

ADR：`ADR-0311`（复用）。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0311 | Maintenance Mode & Hotfix Flow |
| ADR-0312 | Real Runner Review Execution Pack |
| ADR-0313 | v4.0 Planning Framework |
| ADR-0314 | Annual Review & Capability Rebaseline |
| ADR-0315 | Maintenance Quality Gates |
| ADR-0316 | Release Hygiene & Artifacts |
| ADR-0317 | Community & Delivery Readiness |

## 6. Test Plan

新增目标：**>=300 tests**（Phase 57，surefire 口径）；

Phase 1-57 全量目标：**>=14820 tests**（当前 14514）。

| Module | Count |
| --- | ---: |
| 维护流程 | 50 |
| 复审执行包 | 50 |
| v4 规划 | 40 |
| 年度复核 | 40 |
| 维护门禁 | 50 |
| 发布卫生 | 40 |
| 社区交付 | 30 |
| 参数化边缘矩阵 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase57-maintenance-review.md
docs/operations/maintenance-mode.md
docs/deployment/runner-review-execution-pack.md
docs/planning/v4-roadmap.md
docs/planning/rfc-template.md
docs/operations/annual-review.md
docs/operations/release-hygiene.md
docs/security/disclosure-policy.md
docs/benchmark/maintenance-baseline.md
docs/release/v3.7.1-rc-maintenance-notes.md
```

## 8. Engineering Rules

- fix-only：新功能走 v4 规划，不进 develop；
- 修复必须测试先行 + 全量回归 0 failures；
- 复审执行包可执行、可留证；
- 版本/制品可审计（semver + SBOM + 签名策略）；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase57-maintenance-v4-planning`

Commits：

```text
docs: add phase57 ADRs 0311-0317
feat(operations): maintenance mode framework and hotfix flow
feat(operations): runner review execution pack
docs(planning): v4 roadmap and rfc template
feat(operations): annual review and maintenance gates
feat(ci): release hygiene and v3.7.1 rc pipeline
docs: phase57 maintenance release
```

Merge：`merge: integrate Phase57 maintenance mode and v4 planning`

Checkpoint：`checkpoint-before-phase57` / `checkpoint-after-phase57`

## 10. Success Criteria

全部满足：

```text
✅ 维护模式框架（hotfix/backport/补丁流水线）
✅ 真实 Runner 复审执行包（流程/脚本/证据模板）
✅ v4.0 规划框架（路线图/RFC/ADR 预研）
✅ 年度复核机制（文档/基准/能力矩阵）
✅ 维护质量门禁（回归/覆盖/静态/依赖漏洞）
✅ 发布卫生（semver/SBOM/签名/归档）
✅ 社区与交付就绪（CONTRIBUTING/issue/安全披露）
✅ 维护基线（v3.7.1-rc 就绪声明）
✅ 全量回归 >=14820，存储/调度/事务/自治/合规路径零回退
```

## 11. 后续方向（Phase 58+，不在本阶段范围）

- v4.0 特性开发（RFC 获批后分支）；
- 真实 Runner 复审执行（环境可用后）；
- 年度复核执行（脚本产出报告）；
- 项目终期移交（若用户决定收尾归档）。
