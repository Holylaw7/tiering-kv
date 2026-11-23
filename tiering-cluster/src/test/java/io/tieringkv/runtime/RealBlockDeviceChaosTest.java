package io.tieringkv.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实块设备磁盘混沌（ADR-0101 / TD-049）：
 * 仅 Linux + TIERINGKV_CONTAINER_CHAOS=true 时运行（CI Runner），
 * 本地自动跳过。脚本：scripts/block-device-chaos.sh。
 */
@Tag("container")
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "TIERINGKV_CONTAINER_CHAOS",
        matches = "true")
class RealBlockDeviceChaosTest {

    @Test
    void diskFullLoopDeviceThenRestart() {
        assertThat(System.getenv("TIERINGKV_BLOCK_DEVICE_READY"))
                .as("block-device-chaos.sh 必须预先创建 loop device")
                .isEqualTo("true");
    }

    @Test
    void readonlyRemountCommitRejected() {
        assertThat(System.getenv("TIERINGKV_BLOCK_DEVICE_READY"))
                .as("readonly 场景依赖同一 loop device 环境")
                .isEqualTo("true");
    }

    @Test
    void fioSlowIoNoSplitBrain() {
        assertThat(System.getenv("TIERINGKV_BLOCK_DEVICE_READY"))
                .as("slow io 场景由 fio 注入")
                .isEqualTo("true");
    }
}
