# ADR-0311: Maintenance Mode & Hotfix Flow

## Status

Accepted

## Context

v3.7.0 GA 后进入 fix-only 维护模式，需要 hotfix/backport/补丁发布规范。

## Decision

采用维护模式框架：

- fix/* 分支 → develop → main；backport 策略按补丁版本；
- `scripts/hotfix.sh` 创建修复分支并校验；
- release.yml 支持 v3.7.1-rc*/v3.7.1；
- 修复必须测试先行 + 全量回归 0 failures。

## Consequences

优点：修复可控、发布可追。

缺点：新功能需等待 v4.0 规划。

风险：backport 冲突需人工仲裁。

## Implementation

`docs/operations/maintenance-mode.md`、`scripts/hotfix.sh`、
release.yml、`src/test/java/io/tieringkv/operations/MaintenanceModeTest.java`。
