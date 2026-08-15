# ADR-0342: Real Disk Failure Injection

## Status

Accepted

## Context

TD-044/046/049：JVM 层磁盘语义（full/readonly/slow/corrupt）已由
Phase 22/23 覆盖，`block-device-chaos.sh` 与真实 Runner job 存在，
但 Java 侧仅断言环境变量，未在真实块设备上闭环验证存储引擎
（WAL 写入/恢复）在磁盘故障下的行为。

## Decision

- **脚本修正**（block-device-chaos.sh）：loop 设备自动分配
  （`losetup -f` + 状态文件）、`disk-full` 用 dd 填满真实空闲空间
  （非 16M 象征性填充）、`slow`（dmsetup）在环境不支持时显式
  SKIPPED 而非失败、cleanup 按镜像回查 loop 设备幂等清理；
- **真实闭环演练** `RealBlockDeviceExerciseTest`（Linux + 环境变量
  门控，本地跳过）：
  - baseline：在块设备挂载点 WAL 写入 200 条（FSYNC ALWAYS）→
    关闭 → RecoveryManager 恢复 → 无丢失；
  - disk-full：写入 100 条后 Java 侧 fallocate 填满挂载点 →
    新 WAL 打开/追加抛 IOException → 清理填充 → 恢复仍 100 条；
  - readonly：CI 先 remount ro 再运行（环境变量门控）→ 新写入
    失败 + 既有 WAL 只读可恢复；
- **CI 接线**（transaction-e2e.yml block-device-chaos job）：setup →
  baseline/disk-full 演练 → remount ro → readonly 演练 → 恢复 rw →
  cleanup；保留既有 RealBlockDeviceChaosTest 冒烟。

## Alternatives

1. 本地 Linux VM 注入：CI 不可复现；
2. 仅脚本级验证：无存储引擎闭环证据。

## Consequences

优点：真实文件系统故障下的 WAL 崩溃一致性证据；脚本可复现。

真实 Runner 演练发现并修复：RecoveryManager.truncateTail 在干净
尾部也以 WRITE 打开 WAL，只读挂载上恢复必然失败——改为先 READ
探测，仅当存在尾部需截断时才 WRITE 打开（只读文件系统上恢复可
完成，单元测试覆盖）。

缺点：演练仅限 Linux+root Runner；slow 注入在无 device-mapper 的
Runner 上跳过（显式登记）。

风险：GH Runner loop 设备/权限差异——门禁 job 失败即真实阻塞，
不做静默降级（slow 除外）。

## Implementation

`scripts/block-device-chaos.sh` 修正、`runtime/RealBlockDeviceExerciseTest`
新增、`RecoveryManager.truncateTail` 只读语义修复 +
`RecoveryManagerReadonlyTest`、`transaction-e2e.yml` 接线、部署文档。
