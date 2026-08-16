#!/usr/bin/env bash
set -euo pipefail

# 真实块设备磁盘混沌（ADR-0101 / TD-049）：Linux + root 环境。
# 创建 loop device → dmsetup 故障注入 → fio 慢 IO → 只读挂载。
# 用法：sudo scripts/block-device-chaos.sh setup|disk-full|readonly|slow|cleanup

IMAGE=${TIERINGKV_BLOCK_IMAGE:-/tmp/tiering-kv-block.img}
LOOP_STATE=${TIERINGKV_LOOP_STATE:-/tmp/tiering-kv-loop.dev}
MOUNT_DIR=${TIERINGKV_BLOCK_MOUNT:-/mnt/tiering-kv-block}

case "${1:-setup}" in
  setup)
    truncate -s 64M "$IMAGE"
    LOOP_DEV=$(losetup -f)
    losetup "$LOOP_DEV" "$IMAGE"
    echo "$LOOP_DEV" > "$LOOP_STATE"
    # -m 0：保留块归零（ADR-0350 容器级演练修正）——ext4 默认保留
    # 5% 供 root 使用，容器内进程以 root 运行会继续写保留块，
    # 导致“磁盘满”对容器不生效；归零后 ENOSPC 对 root 同样真实。
    mkfs.ext4 -q -m 0 "$LOOP_DEV"
    mkdir -p "$MOUNT_DIR"
    mount "$LOOP_DEV" "$MOUNT_DIR"
    # sudo 下 chown 给调用者（runner），否则 Maven 非 root 无法写入
    chown "${SUDO_UID:-$(id -u)}:${SUDO_GID:-$(id -g)}" "$MOUNT_DIR"
    echo "TIERINGKV_BLOCK_DEVICE_READY=true"
    ;;
  disk-full)
    # 真实填满空闲空间（dd 直到 ENOSPC，非象征性填充）
    dd if=/dev/zero of="$MOUNT_DIR/fill.bin" bs=1M count=256 \
      2>/dev/null || true
    echo "TIERINGKV_BLOCK_DISK_FULL=true"
    ;;
  readonly)
    mount -o remount,ro "$MOUNT_DIR"
    ;;
  slow)
    # dmsetup 延迟注入：无 device-mapper 环境显式 SKIPPED
    LOOP_DEV=$(cat "$LOOP_STATE" 2>/dev/null || true)
    if [ -n "$LOOP_DEV" ] && command -v dmsetup >/dev/null 2>&1; then
      dmsetup create tiering-kv-slow --table \
        "0 $(blockdev --getsz "$LOOP_DEV") delay $LOOP_DEV 0 50"
    else
      echo "TIERINGKV_BLOCK_SLOW=SKIPPED"
    fi
    ;;
  cleanup)
    umount "$MOUNT_DIR" 2>/dev/null || true
    dmsetup remove tiering-kv-slow 2>/dev/null || true
    if [ -f "$LOOP_STATE" ]; then
      losetup -d "$(cat "$LOOP_STATE")" 2>/dev/null || true
      rm -f "$LOOP_STATE"
    else
      # 回查镜像关联的 loop 设备（幂等兜底）
      losetup -j "$IMAGE" 2>/dev/null | cut -d: -f1 \
        | xargs -r losetup -d 2>/dev/null || true
    fi
    rm -f "$IMAGE"
    ;;
  *)
    echo "unknown command: ${1}" >&2
    exit 1
    ;;
esac
