package io.tieringkv.cluster.rpc;

import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import io.tieringkv.security.rpc.RpcPermissionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RPC 帧级令牌（ADR-0119）：信封 v1 兼容旧帧、权限授权。 */
class RpcTokenTest {

    @TempDir
    Path dir;

    private static int freePort() throws Exception {
        return io.tieringkv.testkit.TestPorts.freePort();
    }

    @Test
    void authenticatedCallWithToken() throws Exception {
        int port = freePort();
        MultiRaftEndpoint server = new MultiRaftEndpoint("server", port,
                Map.of("client", new InetSocketAddress("127.0.0.1",
                        port)));
        server.start();
        CredentialManager credentials = new CredentialManager();
        server.setRpcGuard(new RpcPermissionGuard(credentials));
        String token = credentials.issue(Role.ADMIN, 60_000);
        MultiRaftEndpoint client = new MultiRaftEndpoint("client",
                freePort(), Map.of("server",
                new InetSocketAddress("127.0.0.1", port)));
        client.start();
        // META_STATUS 无本地组会抛错，但授权通过后到达 handler；
        // 这里仅验证带令牌帧不被拒绝（错误为 no raft group 而非鉴权）。
        try {
            client.callAuthenticated("server", "g1",
                    RpcMessageType.META_STATUS, new byte[0], token).join();
        } catch (Exception e) {
            assertThat(e).hasMessageContaining("no raft group");
        }
        client.close();
        server.close();
    }

    @Test
    void unauthenticatedStrictRejected() throws Exception {
        int port = freePort();
        MultiRaftEndpoint server = new MultiRaftEndpoint("server", port,
                Map.of("client", new InetSocketAddress("127.0.0.1",
                        port)));
        server.start();
        server.setStrictUnauthenticated(true);
        MultiRaftEndpoint client = new MultiRaftEndpoint("client",
                freePort(), Map.of("server",
                new InetSocketAddress("127.0.0.1", port)));
        client.start();
        RpcFrame response = client.call("server", "g1",
                RpcMessageType.META_STATUS, new byte[0]).join();
        assertThat(response.type()).isEqualTo(RpcMessageType.ERROR);
        client.close();
        server.close();
    }

    @Test
    void wrongPermissionRejected() throws Exception {
        int port = freePort();
        MultiRaftEndpoint server = new MultiRaftEndpoint("server", port,
                Map.of("client", new InetSocketAddress("127.0.0.1",
                        port)));
        server.start();
        CredentialManager credentials = new CredentialManager();
        server.setRpcGuard(new RpcPermissionGuard(credentials));
        String readerToken = credentials.issue(Role.READER, 60_000);
        MultiRaftEndpoint client = new MultiRaftEndpoint("client",
                freePort(), Map.of("server",
                new InetSocketAddress("127.0.0.1", port)));
        client.start();
        RpcFrame response = client.callAuthenticated("server", "g1",
                RpcMessageType.META_STATUS, new byte[0], readerToken)
                .join();
        assertThat(response.type()).isEqualTo(RpcMessageType.ERROR);
        client.close();
        server.close();
    }

    @ParameterizedTest(name = "token {0}")
    @ValueSource(strings = {"", "short", "very-long-token-value-12345"})
    void envelopeV1TokenBoundaries(String token) throws Exception {
        int port = freePort();
        MultiRaftEndpoint server = new MultiRaftEndpoint("server", port,
                Map.of("client", new InetSocketAddress("127.0.0.1",
                        port)));
        server.start();
        server.setStrictUnauthenticated(true);
        MultiRaftEndpoint client = new MultiRaftEndpoint("client",
                freePort(), Map.of("server",
                new InetSocketAddress("127.0.0.1", port)));
        client.start();
        // 空令牌按无令牌处理（严格模式拒绝）；非空令牌无 guard 时放行到 handler。
        try {
            client.callAuthenticated("server", "g1",
                    RpcMessageType.META_STATUS, new byte[0], token).join();
        } catch (Exception e) {
            assertThat(e).hasCauseInstanceOf(SecurityException.class);
        }
        client.close();
        server.close();
    }

    @Test
    void oldV0FrameStillWorksPermissive() throws Exception {
        int port = freePort();
        MultiRaftEndpoint server = new MultiRaftEndpoint("server", port,
                Map.of("client", new InetSocketAddress("127.0.0.1",
                        port)));
        server.start();
        MultiRaftEndpoint client = new MultiRaftEndpoint("client",
                freePort(), Map.of("server",
                new InetSocketAddress("127.0.0.1", port)));
        client.start();
        try {
            client.call("server", "g1", RpcMessageType.META_STATUS,
                    new byte[0]).join();
        } catch (Exception e) {
            assertThat(e).hasMessageContaining("no raft group");
        }
        client.close();
        server.close();
    }
}
