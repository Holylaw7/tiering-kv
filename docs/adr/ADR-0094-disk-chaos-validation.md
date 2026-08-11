# ADR-0094: Disk Chaos Validation

## Status

Accepted

## Context

TD-046：Phase 22 磁盘故障仅 in-JVM 语义注入，真实容器 disk full /
readonly / slow io 未执行。

## Decision

- 真实注入工具：`fallocate`（disk full）、mount readonly（只读）、
  `fio`（慢 IO 延迟注入）、dmsetup（设备级故障）；
- 验证矩阵：
  - disk full → recoverFromRaft → participants 补完，zero lost commit；
  - readonly → commit 失败 → 回滚；
  - slow WAL fsync > election timeout → 无 split brain / 无重复提交；
- 环境受限（Docker Desktop 无特权挂载/设备映射）时：如实登记，
  以容器化 in-JVM 故障注入 + TCP 端到端兜底。

## Alternatives

1. 仅语义测试：缺少真实 IO 路径证据。
2. 云裸金属：环境依赖。

## Consequences

优点：

- 磁盘故障恢复证据链。

缺点：

- 特权环境依赖。

风险：

- 中；由 DiskChaosContainerTest 与 chaos 报告验证。

## Implementation

- `deploy/` 注入脚本 + DiskChaosContainerTest；
- `docs/testing/phase23-chaos-report.md`。
