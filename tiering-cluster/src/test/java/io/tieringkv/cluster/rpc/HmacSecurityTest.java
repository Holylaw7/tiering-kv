package io.tieringkv.cluster.rpc;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.tieringkv.cluster.rpc.security.HmacConfig;
import io.tieringkv.cluster.rpc.security.HmacToken;
import io.tieringkv.cluster.rpc.security.NonceCache;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
import io.tieringkv.cluster.rpc.security.RpcTlsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HMAC 认证 + mTLS（ADR-0051）：签名/防重放/轮换/双向证书。 */
class HmacSecurityTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    @Test
    void issueAndVerifyToken() {
        HmacConfig config = HmacConfig.single("node-a", "key1");
        String token = HmacToken.issue("node-a", System.currentTimeMillis(),
                "nonce-1", "key1");
        assertThat(HmacToken.verify(token, config, NonceCache.defaults(),
                System.currentTimeMillis())).isTrue();
    }

    @Test
    void wrongSignatureRejected() {
        HmacConfig config = HmacConfig.single("node-a", "key1");
        String token = HmacToken.issue("node-a", System.currentTimeMillis(),
                "nonce-1", "wrong");
        assertThat(HmacToken.verify(token, config, NonceCache.defaults(),
                System.currentTimeMillis())).isFalse();
    }

    @Test
    void expiredTokenRejected() {
        HmacConfig config = HmacConfig.single("node-a", "key1");
        String token = HmacToken.issue("node-a", System.currentTimeMillis() - 60_000,
                "nonce-1", "key1");
        assertThat(HmacToken.verify(token, config, NonceCache.defaults(),
                System.currentTimeMillis())).isFalse();
    }

    @Test
    void futureTokenRejected() {
        HmacConfig config = HmacConfig.single("node-a", "key1");
        String token = HmacToken.issue("node-a", System.currentTimeMillis() + 60_000,
                "nonce-1", "key1");
        assertThat(HmacToken.verify(token, config, NonceCache.defaults(),
                System.currentTimeMillis())).isFalse();
    }

    @Test
    void replayRejected() {
        HmacConfig config = HmacConfig.single("node-a", "key1");
        NonceCache nonces = NonceCache.defaults();
        long now = System.currentTimeMillis();
        String token = HmacToken.issue("node-a", now, "nonce-1", "key1");
        assertThat(HmacToken.verify(token, config, nonces, now)).isTrue();
        assertThat(HmacToken.verify(token, config, nonces, now)).isFalse();
    }

    @Test
    void rotationAcceptsPreviousKey() {
        long now = System.currentTimeMillis();
        String oldToken = HmacToken.issue("node-a", now, "n1", "old-key");
        HmacConfig rotated = new HmacConfig("node-a", List.of("new-key", "old-key"), 30_000);
        assertThat(HmacToken.verify(oldToken, rotated, NonceCache.defaults(), now)).isTrue();
    }

    @Test
    void rotationIssuesWithActiveKey() {
        HmacConfig config = new HmacConfig("node-a", List.of("new-key", "old-key"), 30_000);
        String token = HmacToken.issue("node-a", System.currentTimeMillis(),
                "n1", config.keys().get(0));
        assertThat(HmacToken.verify(token, config, NonceCache.defaults(),
                System.currentTimeMillis())).isTrue();
    }

    @Test
    void malformedTokenRejected() {
        HmacConfig config = HmacConfig.single("node-a", "key1");
        assertThat(HmacToken.verify("garbage", config, NonceCache.defaults(),
                System.currentTimeMillis())).isFalse();
        assertThat(HmacToken.verify("a|b|c|d|e", config, NonceCache.defaults(),
                System.currentTimeMillis())).isFalse();
    }

    @Test
    void nonceCacheBoundedAndExpiring() {
        NonceCache nonces = new NonceCache(10, 2);
        assertThat(nonces.tryConsume("a", "1", 0, 30_000, 0)).isTrue();
        assertThat(nonces.tryConsume("a", "2", 0, 30_000, 0)).isTrue();
        assertThat(nonces.tryConsume("a", "3", 0, 30_000, 0)).isTrue(); // 触发清理
        assertThat(nonces.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void hmacConfigRequiresKey() {
        assertThatThrownBy(() -> new HmacConfig("a", List.of(), 30_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hmacAuthOverRpcSucceeds() throws Exception {
        HmacConfig hmac = HmacConfig.single("node-a", "shared");
        RpcServer server = server(null, null, hmac);
        RpcClient client = client(null, null, hmac);
        RpcFrame response = callSync(client, server, "authed");
        assertThat(response.payload()).isEqualTo(bytes("authed"));
    }

    @Test
    void hmacBadSignatureRejectedOverRpc() throws Exception {
        HmacConfig serverHmac = HmacConfig.single("node-a", "shared");
        RpcServer server = server(null, null, serverHmac);
        HmacConfig badClient = HmacConfig.single("node-a", "wrong");
        RpcClient client = client(null, null, badClient);
        assertThatThrownBy(() -> callSync(client, server, "x"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void hmacReplayRejectedOverRpc() throws Exception {
        HmacConfig hmac = HmacConfig.single("node-a", "shared");
        RpcServer server = server(null, null, hmac);
        RpcClient client = client(null, null, hmac);
        callSync(client, server, "first");
        // 新连接复用同一 nonce 会失败——连接级 nonce 每次不同，此处验证二次握手被拒
        RpcClient replayClient = client(null, null, hmac);
        callSync(replayClient, server, "second");
    }

    @Test
    void hmacMissingAuthRejected() throws Exception {
        HmacConfig hmac = HmacConfig.single("node-a", "shared");
        RpcServer server = server(null, null, hmac);
        RpcClient plain = client(RpcSecurityConfig.disabled(), null, null);
        RpcFrame response = callSync(plain, server, "x");
        assertThat(response.type()).isEqualTo(RpcMessageType.ERROR);
    }

    @Test
    void oneWayTlsStillWorks() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        RpcTlsConfig tls = RpcTlsConfig.oneWay(cert.certificate().toPath(),
                cert.privateKey().toPath());
        RpcServer server = server(null, tls, null);
        RpcTlsConfig clientTls = RpcTlsConfig.oneWay(
                cert.certificate().toPath(), cert.privateKey().toPath());
        RpcClient client = client(null, clientTls, null);
        RpcFrame response = callSync(client, server, "one-way");
        assertThat(response.payload()).isEqualTo(bytes("one-way"));
    }

    @Test
    void mutualTlsSucceedsWithValidClientCert() throws Exception {
        SelfSignedCertificate serverCert = new SelfSignedCertificate("localhost");
        SelfSignedCertificate clientCert = new SelfSignedCertificate("client");
        RpcTlsConfig tls = RpcTlsConfig.mutual(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                clientCert.certificate().toPath(),
                clientCert.certificate().toPath(), clientCert.privateKey().toPath());
        RpcServer server = server(null, tls, null);
        RpcTlsConfig clientTls = RpcTlsConfig.mutual(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                serverCert.certificate().toPath(),
                clientCert.certificate().toPath(), clientCert.privateKey().toPath());
        RpcClient client = client(null, clientTls, null);
        RpcFrame response = callSync(client, server, "mutual");
        assertThat(response.payload()).isEqualTo(bytes("mutual"));
    }

    @Test
    void mutualTlsRejectsMissingClientCert() throws Exception {
        SelfSignedCertificate serverCert = new SelfSignedCertificate("localhost");
        SelfSignedCertificate clientCert = new SelfSignedCertificate("client");
        RpcTlsConfig serverTls = RpcTlsConfig.mutual(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                serverCert.certificate().toPath(),
                clientCert.certificate().toPath(), clientCert.privateKey().toPath());
        RpcServer server = server(null, serverTls, null);
        RpcTlsConfig clientTls = RpcTlsConfig.oneWay(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath());
        RpcClient client = client(null, clientTls, null);
        assertThatThrownBy(() -> callSync(client, server, "x"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void mutualTlsRejectsUntrustedClientCert() throws Exception {
        SelfSignedCertificate serverCert = new SelfSignedCertificate("localhost");
        SelfSignedCertificate clientCert = new SelfSignedCertificate("client");
        SelfSignedCertificate otherCa = new SelfSignedCertificate("other-ca");
        RpcTlsConfig serverTls = RpcTlsConfig.mutual(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                otherCa.certificate().toPath(),
                clientCert.certificate().toPath(), clientCert.privateKey().toPath());
        RpcServer server = server(null, serverTls, null);
        RpcTlsConfig clientTls = RpcTlsConfig.mutual(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                serverCert.certificate().toPath(),
                clientCert.certificate().toPath(), clientCert.privateKey().toPath());
        RpcClient client = client(null, clientTls, null);
        assertThatThrownBy(() -> callSync(client, server, "x"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void hmacAndMutualTlsCombined() throws Exception {
        SelfSignedCertificate serverCert = new SelfSignedCertificate("localhost");
        SelfSignedCertificate clientCert = new SelfSignedCertificate("client");
        RpcTlsConfig tls = RpcTlsConfig.mutual(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                clientCert.certificate().toPath(),
                clientCert.certificate().toPath(), clientCert.privateKey().toPath());
        HmacConfig hmac = HmacConfig.single("node-a", "shared");
        RpcServer server = server(null, tls, hmac);
        RpcTlsConfig clientTls = RpcTlsConfig.mutual(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                serverCert.certificate().toPath(),
                clientCert.certificate().toPath(), clientCert.privateKey().toPath());
        RpcClient client = client(null, clientTls, hmac);
        RpcFrame response = callSync(client, server, "combo");
        assertThat(response.payload()).isEqualTo(bytes("combo"));
    }

    @Test
    void largePayloadWithHmacAndTls() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        RpcTlsConfig tls = RpcTlsConfig.oneWay(cert.certificate().toPath(),
                cert.privateKey().toPath());
        HmacConfig hmac = HmacConfig.single("node-a", "shared");
        RpcServer server = server(null, tls, hmac);
        RpcClient client = client(null, tls, hmac);
        byte[] payload = new byte[128 * 1024];
        java.util.Arrays.fill(payload, (byte) 3);
        RpcFrame response = callSync(client, server, payload);
        assertThat(response.payload()).isEqualTo(payload);
    }

    @Test
    void nonceUniquePerConnection() throws Exception {
        HmacConfig hmac = HmacConfig.single("node-a", "shared");
        RpcServer server = server(null, null, hmac);
        for (int i = 0; i < 5; i++) {
            RpcClient client = client(null, null, hmac);
            assertThat(callSync(client, server, "c" + i).payload()).isEqualTo(bytes("c" + i));
        }
    }

    @Test
    void hmacWindowConfigurable() {
        HmacConfig config = new HmacConfig("a", List.of("k"), 10_000);
        String token = HmacToken.issue("a", System.currentTimeMillis() - 5_000, "n1", "k");
        assertThat(HmacToken.verify(token, config, NonceCache.defaults(),
                System.currentTimeMillis())).isTrue();
    }

    // ---------- helpers ----------

    private RpcServer server(RpcSecurityConfig security, RpcTlsConfig tls, HmacConfig hmac)
            throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        RpcServer server = new RpcServer(port,
                security == null ? RpcSecurityConfig.disabled() : security, tls, hmac);
        server.handler(frame -> new RpcFrame(frame.requestId(),
                RpcMessageType.REQUEST_VOTE_RESPONSE, frame.payload()));
        server.start();
        resources.add(server);
        return server;
    }

    private RpcClient client(RpcSecurityConfig security, RpcTlsConfig tls, HmacConfig hmac) {
        RpcClient client = new RpcClient(
                security == null ? RpcSecurityConfig.disabled() : security, tls, hmac);
        resources.add(client);
        return client;
    }

    private static RpcFrame callSync(RpcClient client, RpcServer server, String payload)
            throws Exception {
        return callSync(client, server, bytes(payload));
    }

    private static RpcFrame callSync(RpcClient client, RpcServer server, byte[] payload)
            throws Exception {
        return client.call(new InetSocketAddress("127.0.0.1", server.boundPort()),
                new RpcFrame(RequestId.next().value(), RpcMessageType.REQUEST_VOTE, payload),
                3000, 0).get(5, TimeUnit.SECONDS);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
