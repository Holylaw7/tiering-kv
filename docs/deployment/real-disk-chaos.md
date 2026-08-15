# Real Disk Failure Injection（ADR-0342）

## 目标

在真实 Linux 块设备（loop device + ext4）上闭环验证存储引擎在磁盘
故障（disk-full / readonly）下的崩溃一致性：WAL 写入 → 恢复，
不允许静默丢失。

## 前置

- Linux + root（loop device / mount 权限）；
- CI：GitHub Actions `transaction-e2e.yml` 的 `block-device-chaos`
  job（ubuntu-latest + sudo）；
- 本地 Windows/macOS：演练测试自动跳过（OS/环境变量门控）。

## 脚本

```bash
sudo scripts/block-device-chaos.sh setup      # loop 设备 + ext4 + 挂载
sudo scripts/block-device-chaos.sh disk-full  # dd 填满真实空闲空间
sudo scripts/block-device-chaos.sh readonly   # remount,ro
sudo scripts/block-device-chaos.sh slow       # dmsetup 延迟（无则 SKIPPED）
sudo scripts/block-device-chaos.sh cleanup    # 幂等清理
```

## 演练（RealBlockDeviceExerciseTest，门控）

| 场景 | 步骤 | 断言 |
| --- | --- | --- |
| baseline | WAL 写入 200 条（FSYNC ALWAYS）→ 关闭 → 恢复 | 恢复 200 条 |
| disk-full | 写 100 条 → Java fallocate 填满 → 新 WAL 打开/追加 | IOException；清理后恢复 100 条 |
| readonly | CI 先 remount ro → 新写入 + 既有 WAL 恢复 | 新写入失败；既有 200 条可恢复 |

运行（真实 Runner）：

```bash
TIERINGKV_CONTAINER_CHAOS=true TIERINGKV_BLOCK_DEVICE_READY=true \
  mvn -q -Dtest=RealBlockDeviceChaosTest,RealBlockDeviceExerciseTest \
  -DfailIfNoTests=false test
```

readonly 场景由 CI 在 remount ro 后单独执行
（`-Dtest=RealBlockDeviceExerciseTest#readonlyAppendFailsWithoutLoss`）。

## 已知限制

- slow（dmsetup）在无 device-mapper 的 Runner 上显式 SKIPPED；
- 演练为进程内 WAL 恢复口径（真实 fs 故障，非容器注入）；
  容器级 disk 注入见 container-chaos（TD-046 持续跟踪）。
