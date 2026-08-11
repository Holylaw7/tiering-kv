# ADR-0101: Block Device Disk Chaos

## Status

Accepted

## Context

Phase 23/24 的磁盘混沌为 JVM 语义注入（StorageEngine.put 抛异常，40 项），
覆盖提交拒绝与回滚安全，但未验证真实块设备故障（磁盘写满、只读挂载、
慢 IO）对 Raft 日志与事务提交的影响（TD-049）。

## Decision

在 Linux Runner/VM 使用真实块设备注入：

1. Disk Full：`fallocate` 写满元数据/数据盘后 COMMIT → restart；
2. Readonly：`mount -o remount,ro`，验证 commit rejected、rollback safe；
3. Slow IO：`fio` 延迟注入，验证无 split brain、无重复提交；
4. 新增 `RealBlockDeviceChaosTest`（`@Tag("container")` + 环境变量门控，
   本地自动跳过），JVM 等价套件保留。

## Alternatives

1. 继续 JVM 语义注入：无法发现文件系统/块设备层的真实行为；
2. dmsetup error target 全盘故障：覆盖面窄，无法模拟慢 IO 与只读。

## Consequences

优点：获得真实存储故障证据，TD-049 关闭路径明确。

缺点：需要 Linux + root/loop device 环境，Windows 本地不可执行。

风险：Docker Desktop 权限限制（Phase 22 已知），优先使用 Linux VM 或
GitHub Actions ubuntu-latest（root）。

## Implementation

代码影响范围：

- `scripts/block-device-chaos.sh`（loop/dmsetup/fio/remount 注入）；
- `src/test/java/io/tieringkv/runtime/RealBlockDeviceChaosTest.java`
  （容器门控）；
- `docs/testing/phase25-block-device-chaos-report.md`。
