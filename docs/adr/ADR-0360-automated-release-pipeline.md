# ADR-0360：按标签自动发版与前后端发布门禁

## Status

Accepted（2026-10-24）

## Context

`release.yml` 的触发条件是**逐版本硬编码的标签列表**（`v1.0.0-rc*` …
`v4.1.0`），每新增一个版本都要改一次工作流。实际后果：v5.0.0、v5.0.1、
v5.0.2 三次发布全部是手工执行（本地打 tag → 手工 `gh release create`），
发布说明也要人工准备，容易遗漏。

同时发布门禁只覆盖内核测试，**控制面与前端没有任何发布期校验**——
"内核全绿但前端构建失败"的版本照样能发出去。

## Decision

1. 触发条件改为 `tags: ["v*"]`，与版本号解耦；历史标签
   `checkpoint-*` / `phase-*` 不匹配，不会误触发。
2. 新增 `console` 作业（控制面 `mvn test` + 前端 `npm ci && npm run build`），
   `release` 作业依赖它，形成"内核 + 控制面 + 前端"三重门禁。
3. `scripts/release-notes.sh` 增加 v5/v6 分支：优先读
   `docs/release/v<version>-release-notes.md`，其次从 CHANGELOG 抽取，
   最后回退占位；新增版本不再改脚本。

## Alternatives

- **继续保持硬编码列表**：每次发版都要改工作流并重新评审，易错，否决；
- **改用 `workflow_dispatch` 手工触发**：仍需人工记得执行，且拿不到
  tag 与提交的绑定关系，否决；
- **把 release-notes 直接写死在 YAML 里**：与 CHANGELOG 双份维护，
  必然漂移，否决。

## Consequences

- 打 tag 即发版，Release 与镜像自动产出；
- 发布耗时增加（多一个 console 作业约 3–5 分钟），但能挡住跨层回归；
- 发布说明的"单一事实来源"变成 `docs/release/v<version>-release-notes.md`，
  与 CHANGELOG 并行维护（前者面向用户，后者面向变更记录）。
- 注意：`gh release create` 由流水线执行，**不要**再手工创建同名 Release。

## Implementation

- `.github/workflows/release.yml`（tags + console 作业 + needs）
- `scripts/release-notes.sh`（v5/v6 分支）
- `docs/release/v5.0.3-release-notes.md`
