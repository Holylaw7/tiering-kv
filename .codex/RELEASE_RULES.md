# 发布流程（RELEASE_RULES）

## 1. 版本规范

- Semantic Versioning：`MAJOR.MINOR.PATCH`；
- 变更记录维护在 CHANGELOG.md（Keep a Changelog）。

## 2. 发布前置条件（全部满足才能发布）

- develop 上对应功能已合并且测试通过；
- Benchmark 回归无显著劣化（阈值见 docs/benchmark/benchmark-plan.md）；
- CHANGELOG、README、部署文档已更新；
- 工作树干净，无未提交变更。

## 3. 发布步骤

1. 在 develop 上冻结功能；
2. 更新 CHANGELOG（Unreleased → vX.Y.Z）与版本号（pom.xml）；
3. 运行完整验证：`mvn -B clean verify` + 压力测试（可用时）；
4. 打 tag：`git tag -a vX.Y.Z -m "Release vX.Y.Z"`；
5. 合并 main：`git checkout main && git merge --ff-only develop`；
6. 推送 tag 与 main（CI 流程见 .github/workflows/）。

## 4. 回滚

- 立即回滚：`git reset --hard <上一个 tag>`（需团队确认）；
- 缺陷修复：从 main 拉 hotfix 分支，修复后按流程发布补丁版本。

## 5. 禁止

- 未通过测试/基准直接发布；
- 发布后修改 tag 内容；
- 无 CHANGELOG 记录的发布。
