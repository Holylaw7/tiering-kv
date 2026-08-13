# Phase 50 Task Prompt — Engineering Foundation & Real Runner GA

## 1. Context

Phase 49（v3.2.0 RC）完成真实 Runner 闭环归档与跨监管域联邦一致性，
全量回归 **12241/12241 全绿**（+6 容器门控跳过）。项目功能面已远超
Mini Redis 目标，但产品化深度存在明确短板（工程评审已登记）：

```text
1. pom.xml 版本仍为 0.1.0-SNAPSHOT，与 v3.2.0 宣称不一致；
2. git 无版本 tag（v*），无远程地址，release.yml 从未真实运行；
3. 无日志框架（slf4j/logback 均未接入）；
4. 无覆盖率 / 静态分析 / 依赖审计门禁；
5. benchmark 为手写循环（无 JMH），结果可复现性不足；
6. 真实 Runner 门禁（TD-048/049、K8S-001、REL-001、BM-001/002、
   TD-076 等）从 Phase 25 起多轮"登记待执行"，从未给出最终终态。
```

Phase 50 目标：**Engineering Foundation & Real Runner GA**——先把工程
基座做成可信（版本/制品/日志/质量门禁/基准/CI），再给全部真实环境
门禁唯一终态（可执行项全绿 + 环境阻塞项正式封板，不再继续 defer），
最终发布 **v3.2.0 GA**，并输出产品完成度基线报告。

当前基线：

```text
develop   : d9b5f05 merge: integrate Phase49 real runner closure
            archive and cross-regulatory federation
定位      : Enterprise-ready Distributed Database（v3.2.0 RC）
Tests     : 12241/12241 PASS（另 6 项容器门控本地跳过）
```

## 2. Release 前置项（Phase 25–49 遗留，本阶段给出终态）

| 编号 | 内容 | Phase 49 终态 | Phase 50 处置 |
| --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入 | ENV_BLOCKED | 可执行项实际执行归档；否则正式封板 |
| TD-049 | 真实块设备磁盘混沌 | ENV_BLOCKED | 可执行项实际执行归档；否则正式封板 |
| K8S-001 | kind 集群内验证 | ENV_BLOCKED | 可执行项实际执行归档；否则正式封板 |
| REL-001 | release.yml 真实运行记录 | REGISTERED_RELEASE | v3.2.0 发布流水线交付物 + 运行记录 |
| BM-001 | 跨机 Production Benchmark | ENV_BLOCKED | 跨机项正式封板（交付物就绪 + 阻塞登记） |
| BM-002 | 跨地域 RTT/RTO/RPO | ENV_BLOCKED | 跨地域项正式封板（阻塞登记） |
| TD-051/054/059/060/063/066/069/072/075/078 | 跨地域/容器/磁盘/发布门禁 | ENV_BLOCKED / REGISTERED_RELEASE | 逐项唯一终态，不再滚动 defer |
| TD-076 | S3/Spot 真实网络凭据 | CLOSED(JVM) | 网络项正式封板（真实凭据待 Runner） |

原则（禁止变更）：

- 不修改 Raft safety、MVCC consistency、事务状态机；
- v1.0–v3.1 冻结协议不变，v3.2 扩展 additive（ADR-0103 兼容评审）；
- 版本模型必须与制品一致（pom / tag / release notes / CHANGELOG）；
- 日志禁止输出敏感信息（凭据/密钥/token 一律脱敏）；
- 质量门禁可运行、可解释、达标；不达标必须如实报告；
- 真实 Runner 项：可执行项全绿 + 未执行项正式封板，禁止伪报；
- Benchmark 必须可复现（固定环境/seed/命令），口径如实标注；
- 每阶段完成 `mvn test` 全量 0 failures。

## 3. Phase 50 Goal

目标：**Engineering Foundation & Real Runner GA（v3.2.0 GA）**，
完成 8 个 Goal：

1. 版本模型与制品对齐
2. 结构化日志与敏感信息脱敏
3. 覆盖率 / 静态分析 / 依赖审计质量门禁
4. 真实执行门禁最终处置 v16（可执行项全绿 + 环境阻塞项正式封板）
5. CI 真实执行与 v3.2.0 GA 发布流水线
6. JMH 基准工程化（核心路径可复现基准）
7. v3.2.0 GA 冻结与全量回归
8. 产品完成度基线报告（能力矩阵 product/experimental 标注 +
   技术债终态表 + 成品判定清单）

## 4. Goals

### Goal 1 — 版本模型与制品对齐

目标：消除 pom 版本与发布版本不一致。

交付：

- `pom.xml` 版本模型：引入 `${revision}` 属性（flat 或
  versions-maven-plugin），发布流水线注入 v3.2.0，开发默认
  SNAPSHOT 命名与 tag 联动；
- `build/` 或 `scripts/` 版本校验脚本：pom / release notes /
  CHANGELOG / README 版本号一致性校验；
- 制品：fat jar + Docker image tag + checksums 生成（发布流水线）；
- 验收：版本一致性矩阵测试全绿；`mvn package` 产物可复现。

ADR：`ADR-0262 Version Model & Artifact Alignment`。

### Goal 2 — 结构化日志与敏感信息脱敏

目标：接入生产级日志。

交付：

- `slf4j-api` + `logback-classic` 依赖与 `logback.xml`（控制台 +
  rolling file，级别可配置）；
- 关键路径日志：启动/优雅停机、WAL flush/checkpoint、SSTable
  compaction、迁移、Raft 选举/复制、事务 commit/abort、凭据探测；
- 敏感信息脱敏：凭据/密钥/token/连接串密码统一 Redactor 处理；
- 验收：日志覆盖矩阵 + 脱敏矩阵测试全绿；无 System.out 新增。

ADR：`ADR-0263 Structured Logging & Secret Redaction`。

### Goal 3 — 覆盖率 / 静态分析 / 依赖审计质量门禁

目标：构建期质量门禁可运行。

交付：

- JaCoCo：覆盖率报告 + 阈值校验（line / branch），不达标构建失败
  （阈值由工程评审确定并记录）；
- SpotBugs（或 ErrorProne）+ maven 插件配置与基线；
- 依赖审计：`dependency:analyze` 未使用依赖检查 + OWASP
  dependency-check 配置（CI 可开关）；
- 门禁测试：QualityGateTest 校验 pom 插件配置与阈值脚本；
- 验收：门禁矩阵全绿；报告产物可导出。

ADR：`ADR-0264 Quality Gates`。

### Goal 4 — 真实执行门禁最终处置 v16

目标：门禁收敛从"登记"升级为"终态封板"。

交付：

- `GateConvergenceV16`：每项门禁唯一终态
  （CLOSED / ENV_BLOCKED_FINAL / REGISTERED_RELEASE），移除
  "预期消除阶段"滚动字段，改为"终态理由 + 封板日期"；
- `RunnerClosureArchive` 扩展：终态快照 + 证据 + 封板审计；
- 可执行项（本机 JVM、本地 compose 可用项）实际执行并归档；
- 环境阻塞项正式封板，写入最终门禁登记表（docs/deployment/
  gate-convergence-v16.md）；
- 验收：门禁终态矩阵全绿；无任何项继续标注"待下一 Phase"。

ADR：`ADR-0265 Real Runner Gate Final Disposition v16`。

### Goal 5 — CI 真实执行与 v3.2.0 GA 发布流水线

目标：发布流水线可执行、可留证。

交付：

- release.yml：v3.2.0 标签 + Phase50BenchmarkTest / GA 门禁接入 +
  checksums 与发布产物步骤；
- CI 执行记录（workflow 配置、可运行步骤、产物清单）；若仓库配置
  远程则触发真实运行，否则如实登记"流水线就绪待远程"；
- ReleaseV32GATest：校验流水线配置 + 版本一致性；
- 验收：流水线矩阵全绿；发布产物清单完整。

ADR：`ADR-0266 CI Execution & v3.2 GA Release Pipeline`。

### Goal 6 — JMH 基准工程化

目标：核心路径基准可复现。

交付：

- JMH 依赖 + maven 插件（jmh-core / jmh-generator-annprocess /
  jmh-maven-plugin），独立 `benchmarks/jmh/` 骨架；
- 迁移核心路径：MemTable GET/SET、WAL append、SSTable 随机读
  （mmap + block cache）至少 3 条为 JMH 基准；
- 固定 seed / 参数（fork、warmup、iterations）与运行脚本
  （scripts/benchmark-jmh.sh）；
- 基准报告（docs/benchmark/jmh-core-report.md）+ 口径注明；
- 验收：JMH 矩阵全绿；三条路径基准可一键复现。

ADR：`ADR-0267 JMH Benchmark Engineering`。

### Goal 7 — v3.2.0 GA 冻结与全量回归

目标：v3.2.0 正式冻结。

交付：

- `docs/release/v3.2.0-ga-release-notes.md` 定稿；
- README / ROADMAP / CHANGELOG / AGENT_CONTEXT 同步（v3.2.0 GA）；
- 全量回归 `mvn test` 0 failures，目标 **≥12660**；
- checkpoint-before / after + 合并 develop。

ADR：`ADR-0268 v3.2 GA Freeze & Product Completeness Baseline`。

### Goal 8 — 产品完成度基线报告

目标：给出"成品判定"依据。

交付：

- 能力矩阵：每项能力标注 PRODUCT / EXPERIMENTAL / ADAPTER
  （例如 Redis 命令族 = PRODUCT；SQL/向量/联邦学习/量子授时 =
  EXPERIMENTAL 或 ADAPTER）；
- 技术债终态表：每项 CLOSED / ACCEPTED_LIMITATION /
  ENV_BLOCKED_FINAL；
- 成品判定清单：版本一致、全量绿、门禁终态唯一、文档可独立上手、
  基准可复现、无滚动 defer 项；
- 验收：基线报告矩阵全绿，可与第三方直接评审。

ADR：`ADR-0268`。

## 5. ADR Requirements

必须新增（先 ADR 后代码）：

| ADR | 主题 |
| --- | --- |
| ADR-0262 | Version Model & Artifact Alignment |
| ADR-0263 | Structured Logging & Secret Redaction |
| ADR-0264 | Quality Gates |
| ADR-0265 | Real Runner Gate Final Disposition v16 |
| ADR-0266 | CI Execution & v3.2 GA Release Pipeline |
| ADR-0267 | JMH Benchmark Engineering |
| ADR-0268 | v3.2 GA Freeze & Product Completeness Baseline |

## 6. Test Plan

新增目标：**>=420 tests**（Phase 50，surefire 口径）；

Phase 1-50 全量目标：**>=12660 tests**（当前 12241）。

| Module | Count |
| --- | ---: |
| 版本/制品一致性 | 50 |
| 结构化日志/脱敏 | 60 |
| 质量门禁 | 60 |
| 门禁 v16 终态 | 50 |
| CI/发布 GA | 60 |
| JMH 基准骨架 | 80 |
| 完成度基线 | 40 |
| 参数化边缘矩阵 | 20 |

## 7. Documentation Deliverables

```text
docs/review/phase50-engineering-foundation-ga-review.md
docs/deployment/gate-convergence-v16.md
docs/deployment/ci-execution-and-release-v3.2.md
docs/operations/versioning-and-artifacts.md
docs/operations/logging-guideline.md
docs/operations/quality-gates.md
docs/benchmark/jmh-core-report.md
docs/release/v3.2.0-ga-release-notes.md
docs/review/product-completeness-baseline.md
docs/benchmark/phase50-production-report.md
```

## 8. Engineering Rules

- 版本/制品/文档必须一致，任何一处不一致视为构建失败；
- 日志禁止输出明文敏感信息；
- 质量门禁不达标必须如实报告，禁止降级绕过；
- 真实 Runner 项最终终态唯一：CLOSED / ENV_BLOCKED_FINAL /
  REGISTERED_RELEASE，禁止继续"待下一 Phase"；
- v1.0–v3.1 冻结协议不变；v3.2 扩展 additive；
- 基准固定 seed/参数，口径（LOCAL / CROSS_MACHINE）如实标注；
- 使用 Conventional Commits；每阶段完成 `mvn test` 全量 0 failures。

## 9. Git Workflow

Branch：`feature/phase50-engineering-foundation-real-runner-ga`

Commits：

```text
docs: add phase50 ADRs 0262-0268
build(project): version model and artifact alignment
feat(ops): structured logging and secret redaction
build(project): quality gates coverage static analysis dependency audit
feat(gates): real runner gate final disposition v16
feat(ci): v3.2 ga release pipeline and execution records
perf(benchmark): jmh benchmark engineering
docs: phase50 release
```

Merge：`merge: integrate Phase50 engineering foundation and v3.2 ga`

Checkpoint：`checkpoint-before-phase50` / `checkpoint-after-phase50`

## 10. Success Criteria

全部满足：

```text
✅ 版本模型一致（pom / tag / release notes / CHANGELOG 校验全绿）
✅ 日志框架接入 + 敏感信息脱敏 + 关键路径覆盖
✅ 覆盖率/静态分析/依赖审计门禁可运行且达标
✅ 门禁 v16：每项唯一终态，可执行项全绿，环境阻塞项正式封板
✅ v3.2.0 GA 发布流水线与 release notes 定稿
✅ JMH 基准骨架 + 至少 3 条核心路径可复现
✅ 全量回归 >=12660，存储/调度/事务/自治/合规路径零回退
✅ 产品完成度基线报告（product/experimental 标注 + 技术债终态表）
```

## 11. 后续方向（Phase 51+，不在本阶段范围）

- Redis 命令族补齐（字符串/TTL/多键/SCAN/DBSIZE 等，产品主线深挖）；
- 数据结构与协议演进（hash/list/set/zset、RESP3、Pub/Sub）；
- 原型转生产（SQL 引擎、HNSW、控制台）；
- 分布式正确性验证（Jepsen 式线性一致性、Raft 边角、升级/备份演练）；
- 文档产品化（README 重写、API 参考、运维手册）与正式发布。
