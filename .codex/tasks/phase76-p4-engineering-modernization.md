# Phase 76 — P4：工程现代化（多模块评估 / JDK 21 / 命令表驱动）

## Context

optimization-roadmap P4 三项 + 发布收尾。基线：P0-P3 全部完成，
v3.7.1 / v4.0.0-rc1 已发布，全量 14910 tests。

## Goal

1. P4a：多模块拆分评估（TD-001）→ ADR-0346 + PackageBoundaryTest
2. P4b：JDK 21 正式升级（pom + CI + 全量回归）
3. P4c：命令表驱动重构（CommandRegistry，冻结计数不变）
4. 发布收尾：Phase 58 状态归档 + v4.1.0 GA tag/release
5. 非阻塞项：TD-038（CLUSTER ASK）、TD-051（JFR/分片）

## 验收

- ADR-0346/0347 批准；PackageBoundaryTest 全绿
- JDK 21 全量回归 0 failures + 真实 Runner 门禁 7/7
- 命令冻结计数不变（ReleaseV37Test 等回归通过）
- v4.1.0 tag + release；ROADMAP 阶段全绿；TD-001/038/051 处置归档

## 状态

- P4a ✅（2026-08-16）：ADR-0346 + PackageBoundaryTest（4 项），
  TD-001 关闭；
- P4b ✅（2026-08-16）：ADR-0347，pom release 21 + 5 个 workflow
  temurin 21 + 容器镜像 21；JDK 21 本地全量 14914 项，3 个时序
  flaky（FlushSchedulerManager/MetadataNetworkRaftExtended/
  ChaosCluster）单独重跑 47/47 通过；
- P4c 命令表驱动重构（进行中）。
- P4c ✅（2026-08-16）：ADR-0348，CommandCatalog 默认表 129 项 +
  动态 info/exec/command（注册表冻结 132 不变，ReleaseV36/V37 回归
  通过），CommandCatalogTest 3 项；JDK 21 全量 14917 项 0 failures。
- TD-038 ✅（2026-08-16）：ASKING single-shot + 迁移 slot 直通，
  GatewayIntegrationTest 33/33；
- TD-051 ✅（2026-08-16）：测试分片（shard-tests.sh 2 shard）+
  JFR 采集管线（tieringkv.argline + jfr-smoke job）；
- 全量回归 14919 项（1 个已知 ChaosCluster flaky 单独重跑 20/20
  通过）。
