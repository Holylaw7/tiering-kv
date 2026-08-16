package io.tieringkv.runtime;

import io.tieringkv.distributed.harness.RespClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 容器级磁盘故障闭环（ADR-0350 / TD-046/049）：仅 Linux +
 * TIERINGKV_CONTAINER_CHAOS=true 时运行（CI Runner），本地跳过。
 *
 * <p>前置：block-device-chaos.sh 已把 loop 设备 bind 为 txn-meta
 * 容器 /data，事务栈已启动（gateway 127.0.0.1:6379）。
 * TIERINGKV_BLOCK_EXPECT=failure：故障期 SET 必须失败（不静默成功）；
 * =recovered：恢复期 SET/GET 必须成功（有界重试吸收 Raft 恢复）。
 */
@Tag("container")
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "TIERINGKV_CONTAINER_CHAOS",
        matches = "true")
class RealContainerDiskChaosTest {

    private static final int IO_TIMEOUT_MILLIS = 15_000;

    private static String gatewayHost() {
        return System.getenv().getOrDefault(
                "TIERINGKV_GATEWAY_HOST", "127.0.0.1");
    }

    private static int gatewayPort() {
        return Integer.parseInt(System.getenv().getOrDefault(
                "TIERINGKV_GATEWAY_PORT", "6379"));
    }

    private static String expectedMode() {
        return System.getenv().getOrDefault(
                "TIERINGKV_BLOCK_EXPECT", "recovered");
    }

    @Test
    void setMustFailUnderDiskFault() {
        assertThat(expectedMode()).isEqualTo("failure");
        assertThatThrownBy(() -> {
            try (RespClient client = new RespClient(
                    gatewayHost(), gatewayPort())) {
                client.setTimeout(IO_TIMEOUT_MILLIS);
                client.put("disk-fault-key", "v");
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    void setGetSucceedsAfterRecovery() throws Exception {
        assertThat(expectedMode()).isEqualTo("recovered");
        IOException last = null;
        int succeeded = 0;
        for (int attempt = 0; attempt < 10 && succeeded < 3; attempt++) {
            try (RespClient client = new RespClient(
                    gatewayHost(), gatewayPort())) {
                client.setTimeout(IO_TIMEOUT_MILLIS);
                String key = "disk-recovered-" + attempt;
                client.put(key, "v" + attempt);
                assertThat(client.get(key)).isEqualTo("v" + attempt);
                succeeded++;
            } catch (IOException e) {
                last = e; // 恢复期 Raft/元数据就绪可能仍需短暂重试
            }
        }
        if (succeeded < 3 && last != null) {
            throw last;
        }
        assertThat(succeeded).as(
                "磁盘恢复后 3 轮 SET/GET 应成功").isEqualTo(3);
    }
}
