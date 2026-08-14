#!/usr/bin/env bash
set -euo pipefail

# 真实块设备磁盘混沌（ADR-0101 / TD-049）：Linux + root 环境。
# 创建 loop device → dmsetup 故障注入 → fio 慢 IO → 只读挂载。
# 用法：sudo scripts/block-device-chaos.sh setup|disk-full|readonly|slow|cleanup

IMAGE=${TIERINGKV_BLOCK_IMAGE:-/tmp/tiering-kv-block.img}
LOOP_DEV=${TIERINGKV_LOOP_DEV:-/dev/loop7}
MOUNT_DIR=${TIERINGKV_BLOCK_MOUNT:-/mnt/tiering-kv-block}

case "${1:-setup}" in
  setup)
    truncate -s 64M "$IMAGE"
    losetup "$LOOP_DEV" "$IMAGE"
    mkfs.ext4 -q "$LOOP_DEV"
    mkdir -p "$MOUNT_DIR"
    mount "$LOOP_DEV" "$MOUNT_DIR"
    chown "$(id -u):$(id -g)" "$MOUNT_DIR"
    echo "TIERINGKV_BLOCK_DEVICE_READY=true"
    ;;
  disk-full)
    fallocate -l 16M "$MOUNT_DIR/fill.bin"
    ;;
  readonly)
    mount -o remount,ro "$MOUNT_DIR"
    ;;
  slow)
    # fio 延迟注入：块设备 50ms 延迟（需要 device mapper 权限）
    dmsetup create tiering-kv-slow --table \
      "0 $(blockdev --getsz "$LOOP_DEV") delay $LOOP_DEV 0 50"
    ;;
  cleanup)
    umount "$MOUNT_DIR" 2>/dev/null || true
    dmsetup remove tiering-kv-slow 2>/dev/null || true
    losetup -d "$LOOP_DEV" 2>/dev/null || true
    rm -f "$IMAGE"
    ;;
  *)
    echo "unknown command: ${1}" >&2
    exit 1
    ;;
esac
