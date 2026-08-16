# ADR-0350: Container-Level Disk Fault Injection (TD-046/049 Closure)

## Status

Accepted

## Context

TD-046/TD-049：真实容器 disk full / readonly / slow io 注入未完成
（Docker Desktop 权限 / 托管 Runner 限制）。

现状：

- ADR-0342 `block-device-chaos.sh` 已在真实 Linux Runner 完成宿主级
  闭环：loop 设备 + ext4 + 挂载 + disk-full/readonly 注入，
  `RealBlockDeviceExerciseTest` 验证 WAL 写入/恢复语义（JVM 层）；
- 缺口：**真实事务容器**（txn-meta 等）运行在故障块设备上的行为
  未验证——容器内 WAL/元数据写入在磁盘满/只读时是否失败而不静默
  丢失。

## Decision

**容器级磁盘故障闭环（真实 Runner）**：

1. `docker-compose.transaction.yml` 的 `txn-meta:/data` 卷改为
   `${TIERINGKV_BLOCK_MOUNT:-txn-meta}:/data`：未设置时保持命名卷，
   设置时把 loop 设备挂载点 bind 为容器 `/data`（真实块设备）；
2. 新增 `RealContainerDiskChaosTest`（Linux + TIERINGKV_CONTAINER_CHAOS
   门控）：经真实 RESP 网关单次 SET——
   - `TIERINGKV_BLOCK_EXPECT=failure`：故障期必须失败（不静默成功）；
   - `TIERINGKV_BLOCK_EXPECT=recovered`：恢复期必须成功；
3. CI `block-device-chaos` job 扩展：
   setup → compose up（bind）→ 预检冒烟 → disk-full 注入 → failure
   断言 → 释放空间 → recovered 断言 → readonly 注入 → failure 断言
   → 恢复 rw → recovered 断言 → cleanup；
4. slow io：托管 Runner 无 device-mapper 时显式 SKIPPED（脚本已有
   分支），在特权/自托管 Runner 可按需启用，不作为门禁阻塞。

## Alternatives

1. docker exec 在容器内 dd 填满：会写满宿主 overlay，风险不可控；
2. 仅 JVM 层验证：无法覆盖真实进程/容器挂载语义；
3. 放弃：TD-046/049 保持跟踪。

## Consequences

优点：真实容器 + 真实块设备 + 真实网关三层闭环；disk-full/readonly
获得真实 Runner 证据；TD-046/049 可关闭。

缺点：CI job 增加 compose 启停开销（分钟级）；slow io 仍需特权环境。

风险：低——bind 仅作用于 txn-meta `/data`；cleanup 幂等。

## Implementation

`docker-compose.transaction.yml`（卷 env 插值）、
`runtime/RealContainerDiskChaosTest`、`transaction-e2e.yml`
block-device-chaos job、本 ADR；ROADMAP TD-046/049 关闭。
