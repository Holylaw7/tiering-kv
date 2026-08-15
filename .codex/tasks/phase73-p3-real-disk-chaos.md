# Phase 73 — P3：真实磁盘故障注入闭环

## Context

P3 第一项。基线：JVM 磁盘语义已覆盖（Phase 22/23），
block-device-chaos.sh 与 CI job 存在但 Java 侧未做真实闭环验证。

## Goal

1. ADR-0342 已批准（本阶段）
2. 脚本修正：loop 自动分配、disk-full 真实填满、slow 优雅跳过、
   cleanup 幂等
3. RealBlockDeviceExerciseTest：baseline 写/恢复 + disk-full 失败
   无丢失 + readonly 失败无丢失（Linux+环境变量门控）
4. CI block-device-chaos job 接线完整闭环
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 脚本 | scripts/block-device-chaos.sh（修正） |
| 演练 | runtime/RealBlockDeviceExerciseTest |
| CI | .github/workflows/transaction-e2e.yml |
| 文档 | ADR-0342、deployment/real-disk-chaos.md、roadmap、CHANGELOG |

## Test Plan

- 本地（Windows）：演练类自动跳过（OS/环境门控），编译+既有测试
  回归 0 failures
- 真实 Runner：setup → baseline/disk-full → readonly → cleanup，
  演练断言通过
- 全量回归 0 failures；新增测试 ≥3（门控）

## 验收

- ADR-0342 已批准；Conventional Commit 拆分
- transaction-e2e block-device-chaos job 全绿（真实 Runner 证据）
- TD-044/046/049 关闭或标记为部分关闭（真实注入证据）
