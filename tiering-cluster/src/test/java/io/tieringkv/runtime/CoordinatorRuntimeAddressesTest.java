package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行时 RPC 地址表（ADR-0343 真实 Runner 修正）：gateway/coordinator
 * 必须能解析 metadata 与全部 region host，否则事务 RPC 立即失败。
 */
class CoordinatorRuntimeAddressesTest {

    @Test
    void includesSelfMetadataAndRegionHosts() {
        Map<String, InetSocketAddress> addresses =
                CoordinatorRuntime.buildRpcAddresses(
                        "gateway-1", 7201, "metadata", 7300,
                        "r1@participant-a:7100,r2@participant-b:7100");
        assertThat(addresses).containsKeys(
                "gateway-1", "metadata", "participant-a", "participant-b");
        assertThat(addresses.get("gateway-1").getPort()).isEqualTo(7201);
        assertThat(addresses.get("metadata").getPort()).isEqualTo(7300);
        assertThat(addresses.get("participant-a").getPort())
                .isEqualTo(7100);
        assertThat(addresses.get("participant-b").getHostString())
                .isEqualTo("participant-b");
    }

    @Test
    void rejectsMalformedRegionSpec() {
        assertThatThrownBy(() -> CoordinatorRuntime.buildRpcAddresses(
                "c", 7200, "m", 7300, "r1@no-port"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorFrameFailsMetadataProposal() {
        RpcFrame error = new RpcFrame(1L, RpcMessageType.ERROR,
                "disk full".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CoordinatorRuntime
                .decodeMetadataDecision(error))
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasMessageContaining("disk full");
    }

    @Test
    void okFrameReturnsDecisionIndex() {
        RpcFrame ok = new RpcFrame(1L,
                RpcMessageType.TXN_METADATA_RESPONSE, new byte[0]);
        assertThat(CoordinatorRuntime.decodeMetadataDecision(ok))
                .isEqualTo(1L);
    }
}
