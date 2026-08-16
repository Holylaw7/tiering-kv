# Tiering-KV 维护期运行手册（Maintenance Runbook）

> 适用：v4.1.0 GA 之后的维护模式。开发阶段已完结（Phase 0–74、
> P0–P4、技术债 49/49），本手册是日常维护、依赖升级、发布与
> 回滚的可执行 SOP。基线状态见
> [final-project-closure.md](../review/final-project-closure.md)。

## 1. 维护目标与基线

- 稳定基线：**v4.1.0**（GitHub Release + GHCR
  `ghcr.io/holylaw7/tiering-kv:v4.1.0`）；
- 质量基线：全量 **约 6,736 个测试方法 / 14,950 次测试执行 /
  0 failures**（Surefire 口径，JDK 21），
  Trivy 0 漏洞，真实 Runner 门禁全绿；
- 兼容承诺：补丁（v4.1.x）不破坏 RESP 协议 / 存储格式 / RPC 信封 /
  API；破坏性变更必须升主版本并重新走完整发布门禁。

## 2. 日常工作流

```text
develop 分支集成
  → 本地全量回归
  → 真实 Runner 门禁（build / test 三分片 / transaction-e2e）
  → main 合并（fast-forward）
  → 发布时打 tag（release workflow 自动执行）
```

规则：

- Commit 使用 Conventional Commits（feat/fix/perf/docs/ci/build/refactor/test）；
- 任何修改遵循：需求 → ADR（如需）→ TDD → 全量回归 → 门禁 → commit；
- 文档必须同步：README / CHANGELOG / ROADMAP / ADR / AGENT_CONTEXT，
  禁止代码与文档不一致；
- 修改前如涉及核心模块，先建 checkpoint tag
  （`git tag checkpoint-before-<topic>`），失败可回滚。

## 3. 依赖升级 SOP（安全类）

1. 触发源：release 门禁 Trivy 扫描失败，或 Dependabot 提示；
2. 先定位漏洞坐标与修复版本（Trivy 输出 / NVD / GHSA）；
3. 升级 pom（属性统一管理）；传递依赖用 `dependencyManagement` 覆盖
   （参考 v4.1.0 的 jackson/fabric8/okhttp/okio 案例）；
4. 兼容验证：fabric8/okhttp 升级后必须跑
   `TieringKVOperatorTest` / `TieringKVReconcilerTest`
   （mockwebserver 版本需与主包一致，3.x/4.x 不混用）；
5. 全量回归（排除 benchmark 组）：
   `mvn '-Dsurefire.excludedGroups=benchmark' test`；
6. 推送后跑 release 门禁确认 Trivy 0 漏洞；
7. CHANGELOG 记录升级与理由。

## 4. 已知 flaky 与重跑策略

真实 Runner 偶发 Raft/Chaos 时序抖动（共享 runner 资源波动），
CI 已配置 `surefire.rerunFailingTestsCount=1` 与 job 超时兜底：

- 已知类：ChaosValidationTest、ChaosClusterTest、
  MetadataNetworkRaftExtendedTest、MetadataNetworkRaftTest、
  MultiRaftTransportTest、LeaderTransferTest、
  FlushSchedulerManagerTest（时序敏感）；
- 处置：单独重跑该类验证（`mvn -Dtest=<Class> test`）；
  若单独重跑稳定通过 → 判定环境 flaky，记录不掩盖；
  若稳定失败 → 按真实缺陷流程修复（参考 v4.1.0 容器级演练
  发现并修复静默成功缺陷的案例）；
- 新增测试类无需改 CI：三分片脚本按类列表自动包含。

## 5. 发布 check-list（v4.1.x 补丁）

1. `pom.xml` revision 更新（如 4.1.1）并同步
   CHANGELOG（新增版本 section）与 README（发布历史）；
2. 全量回归 0 failures + Trivy 0 漏洞；
3. main/develop 同步后打 tag：
   `git tag v4.1.1 && git push origin v4.1.1`；
4. release workflow 自动执行：分片测试 → Benchmark（71 类）→
   Trivy → GHCR 镜像 → 校验和 → Release notes → GitHub Release；
5. 验证 GitHub Release 与 GHCR 标签，归档阶段记录；
6. 若 tag 需移动：本地 `git tag -f` 后
   `git push origin :refs/tags/<tag>` 再推（仅限未发布资产）。

## 6. 混沌演练前置条件

- 网络混沌（ADR-0343）：Linux Runner + Docker；后端四容器
  `NET_ADMIN` + 镜像含 iproute2；`TIERINGKV_NETWORK_CHAOS=true`；
- 磁盘混沌（ADR-0342/0350）：Linux root + loop 设备；
  `block-device-chaos.sh setup` 使用 `mkfs.ext4 -m 0`（保留块归零），
  disk-full 为无限小块填充 + `stat -f %a` 校验 ≤1 块；
  容器级（ADR-0350）：`TIERINGKV_BLOCK_MOUNT=/mnt/tiering-kv-block`
  bind 为 txn-meta `/data`；readonly 注入需先 stop 容器再 remount；
- slow io：托管 Runner 无 device-mapper 时显式 SKIPPED，
  特权/自托管 Runner 可启用（不阻塞门禁）。

## 7. 环境限制与注意事项

- 以 Linux CI 为准：Windows 本地存在文件句柄/`@TempDir` 清理时序
  差异（v4.1.0 已修复 recover 日志流泄漏；新增文件写入代码注意
  try-with-resources）；
- 托管 Runner：无 loop 设备持久化、无 device-mapper、共享 CPU
  （Raft 时序测试可能偶发抖动）；
- GitHub Actions 日志：失败时下载 run 日志 zip（
  `actions/runs/<id>/logs`），优先看 job step 与 surefire 报告。

## 8. 定期维护任务

| 频率 | 任务 |
| --- | --- |
| 每周 | benchmark workflow（性能回归基线）+ Trivy 依赖扫描 |
| 每两周 | 全量回归一次 + 已知 flaky 清单复核 |
| 每季度 | 技术债/ADR 审计、依赖大版本评估（fabric8/Netty/Jackson）、
  维护期文档同步检查 |
| 按需 | 真实 Runner 暴露缺陷修复（遵循 TDD + 门禁） |

## 9. 回滚指南

- 代码回滚：`git revert <commit>`（保留历史）或 checkpoint tag
  `git reset --hard checkpoint-before-<topic>`（仅本地未推送场景）；
- 发布回滚：GitHub Release 可删除（softprops 创建的资产）；
  GHCR 镜像按 tag 覆盖重推（旧 tag 不删除，避免拉取破坏）；
- 数据安全：回滚不改变已落盘数据格式；若补丁引入格式变更，
  必须先升级后回滚策略（先写 CHANGELOG/ADR 再动手）。
