# ADR-0266: CI Execution & v3.2 GA Release Pipeline

## Status

Accepted

## Context

release.yml 已覆盖 v1.0–v3.2 标签，但仓库无远程、无版本 tag，流水线
从未真实运行；发布产物（checksums）缺失。

## Decision

采用可执行、可留证的 GA 发布流水线：

- release.yml：v3.2.0 标签 + Phase50BenchmarkTest/GA 门禁 + SHA-256
  checksums 步骤 + 发布产物清单；
- 仓库配置远程后触发真实运行；未配置时如实登记"流水线就绪待远程"；
- ReleaseV32GATest 校验流水线配置与版本一致性；
- `docs/deployment/ci-execution-and-release-v3.2.md` 记录执行清单。

## Alternatives

1. 不接入流水线：发布只能手工；
2. 伪报已执行：违反真实原则；
3. 只加标签不加基准/checksums：发布质量无法回归。

## Consequences

优点：发布可复现、产物可校验、执行可留证。

缺点：真实运行依赖远程仓库与 Runner。

风险：流水线配置漂移，需要门禁测试持续校验。

## Implementation

`.github/workflows/release.yml`、
`src/test/java/io/tieringkv/ci/ReleaseV32GATest.java`、
`docs/deployment/ci-execution-and-release-v3.2.md`。
