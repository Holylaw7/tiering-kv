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
 * 真实网络混沌演练（ADR-0343 / TD-035）：仅 Linux +
 * TIERINGKV_NETWORK_CHAOS=true 时运行（CI Runner），本地自动跳过。
 *
 * <p>经真实 RESP 网关（默认 127.0.0.1:6379）执行 SET/GET 往返：
 * delay/loss/recovered 阶段最终一致（带重试）；partition 阶段有界
 * 时间内必须失败（不静默成功）。
 */
@Tag("container")
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "TIERINGKV_NETWORK_CHAOS",
        matches = "true")
class RealNetworkChaosTest {

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
                "TIERINGKV_NETEM_EXPECT", "recovered");
    }

    @Test
    void setGetRoundTripUnderNetem() throws Exception {
        assertThat(expectedMode()).isIn("delay", "loss", "recovered");
        int succeeded = 0;
        IOException last = null;
        for (int attempt = 0; attempt < 10 && succeeded < 5; attempt++) {
            try (RespClient client = new RespClient(
                    gatewayHost(), gatewayPort())) {
                client.setTimeout(IO_TIMEOUT_MILLIS);
                String key = "netem-" + attempt;
                client.put(key, "v" + attempt);
                assertThat(client.get(key)).isEqualTo("v" + attempt);
                succeeded++;
            } catch (IOException e) {
                last = e; // 重试吸收 loss 抖动
            }
        }
        assertThat(succeeded)
                .as("netem(%s) 下 5 轮 SET/GET 应最终成功", expectedMode())
                .isEqualTo(5);
        if (last != null) {
            throw last;
        }
    }

    @Test
    void partitionBlocksRoundTrip() {
        assertThat(expectedMode()).isEqualTo("partition");
        assertThatThrownBy(() -> {
            try (RespClient client = new RespClient(
                    gatewayHost(), gatewayPort())) {
                client.setTimeout(IO_TIMEOUT_MILLIS);
                client.put("partition-key", "v");
            }
        }).isInstanceOf(IOException.class);
    }
}
