# Phase 25 真实块设备磁盘混沌报告

Phase 25 · 2026-08-11 · TD-049 交付物

## 1. 注入方案（scripts/block-device-chaos.sh）

| Case | 注入 | 验证 |
| --- | --- | --- |
| Disk Full | `fallocate` 写满挂载盘后 COMMIT → restart | Raft recovery，无已提交事务丢失 |
| Readonly | `mount -o remount,ro` | commit rejected，rollback safe |
| Slow IO | `dmsetup delay` + `fio` | no split brain、no duplicate commit |

## 2. 门控测试

`RealBlockDeviceChaosTest`（`@Tag("container")` + Linux +
`TIERINGKV_CONTAINER_CHAOS=true`）：

- 本地（Windows）自动跳过（全量回归 Skipped=6，其中 3 项为本套件）；
- CI block-device-chaos job：setup → disk-full → cleanup → setup →
  remount,ro → 门控测试 → cleanup。

## 3. 执行状态（如实记录）

- JVM 语义等价矩阵（RealDiskChaos 40 + 参数化 57 项）本地全绿；
- 真实块设备注入脚本与 CI job 已交付；
- 真实 loop/dmsetup/fio 执行记录待 Linux Runner 触发（Docker Desktop
  权限限制与 Phase 22 已知一致）。

## 4. 验收

三个场景在 Linux Runner 全绿后关闭 TD-049。
