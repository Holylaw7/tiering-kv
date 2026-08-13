# Maintenance Mode

## 流程

- 修复：`fix/<desc>` 分支（`scripts/hotfix.sh <desc>`）；
- 校验：`mvn -q test` 0 failures + Conventional Commit；
- 合并：fix/* → develop → main；
- backport：按补丁版本 cherry-pick（冲突人工仲裁）。

## 补丁版本

v3.7.1-rc* / v3.7.1（release.yml 已支持）；GA 语义不变。
