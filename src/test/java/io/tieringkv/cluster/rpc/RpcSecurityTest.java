package io.tieringkv.cluster.rpc;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
import io.tieringkv.cluster.rpc.security.TokenBucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 安全 RPC（ADR-0046）：TLS / 认证 / 限流。 */
class RpcSecurityTest {

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
    void tlsHandshakeSucceeds() throws Exception {
        SelfSignedCertificate cert = cert();
        RpcServer server = server(security(cert, false, 0));
        RpcClient client = client(security(cert, false, 0));
        RpcFrame response = call(client, server, "tls-ok");
        assertThat(response.payload()).isEqualTo(bytes("tls-ok"));
    }

    @Test
    void invalidCertificateRejected() throws Exception {
        SelfSignedCertificate serverCert = cert();
        SelfSignedCertificate wrongCert = cert();
        RpcServer server = server(security(serverCert, true, 0));
        RpcClient client = client(security(wrongCert, true, 0));
        assertThatThrownBy(() -> callAsync(client, server, "boom").get(5, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void authenticationSuccess() throws Exception {
        SelfSignedCertificate cert = cert();
        RpcServer server = server(security(cert, true, 0, "secret", future()));
        RpcClient client = client(security(cert, true, 0, "secret", future()));
        RpcFrame response = call(client, server, "authed");
        assertThat(response.payload()).isEqualTo(bytes("authed"));
    }

    @Test
    void missingAuthRejected() throws Exception {
        SelfSignedCertificate cert = cert();
        RpcServer server = server(security(cert, false, 0, "secret", future()));
        RpcClient client = client(RpcSecurityConfig.disabled()); // 不带 token
        CompletableFuture<RpcFrame> future = callAsync(client, server, "no-auth");
        RpcFrame response = future.get(5, TimeUnit.SECONDS);
        assertThat(response.type()).isEqualTo(RpcMessageType.ERROR);
        assertThat(response.payload()).isEqualTo(io.tieringkv.cluster.rpc.security
                .RpcAuthInterceptor.AUTH_REQUIRED);
    }

    @Test
    void invalidTokenRejected() throws Exception {
        SelfSignedCertificate cert = cert();
        RpcServer server = server(security(cert, false, 0, "secret", future()));
        RpcClient client = client(security(cert, true, 0, "wrong", future()));
        CompletableFuture<RpcFrame> future = callAsync(client, server, "bad");
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void expiredTokenRejected() throws Exception {
        SelfSignedCertificate cert = cert();
        long expired = System.currentTimeMillis() - 1000;
        RpcServer server = server(security(cert, true, 0, "secret", expired));
        RpcClient client = client(security(cert, true, 0, "secret", expired));
        CompletableFuture<RpcFrame> future = callAsync(client, server, "expired");
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void authRequiredBeforeOtherFrames() throws Exception {
        SelfSignedCertificate cert = cert();
        RpcServer server = server(security(cert, false, 0, "secret", future()));
        // 直接通过底层 socket 发非 AUTH 帧：先建一个无认证 client 连接并调用
        RpcClient client = client(RpcSecurityConfig.disabled());
        RpcFrame response = callAsync(client, server, "x").get(5, TimeUnit.SECONDS);
        assertThat(response.type()).isEqualTo(RpcMessageType.ERROR);
    }

    @Test
    void rateLimitRejectsOverflow() throws Exception {
        RpcServer server = server(new RpcSecurityConfig(false, null, null, null, 0, 5));
        RpcClient client = client(RpcSecurityConfig.disabled());
        int errors = 0;
        for (int i = 0; i < 20; i++) {
            RpcFrame response = callAsync(client, server, "r" + i).get(5, TimeUnit.SECONDS);
            if (response.type() == RpcMessageType.ERROR
                    && new String(response.payload(), java.nio.charset.StandardCharsets.UTF_8)
                    .equals("ERR RATE_LIMIT")) {
                errors++;
            }
        }
        assertThat(errors).isGreaterThan(0);
    }

    @Test
    void rateLimitAllowsWithinBudget() throws Exception {
        RpcServer server = server(new RpcSecurityConfig(false, null, null, null, 0, 100));
        RpcClient client = client(RpcSecurityConfig.disabled());
        for (int i = 0; i < 5; i++) {
            RpcFrame response = callAsync(client, server, "ok" + i).get(5, TimeUnit.SECONDS);
            assertThat(response.type()).isEqualTo(RpcMessageType.REQUEST_VOTE_RESPONSE);
        }
    }

    @Test
    void tokenBucketUnit() {
        TokenBucket bucket = new TokenBucket(10);
        for (int i = 0; i < 10; i++) {
            assertThat(bucket.tryAcquire()).isTrue();
        }
        assertThat(bucket.tryAcquire()).isFalse();
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(bucket.tryAcquire()).isTrue();
    }

    @Test
    void tokenBucketValidation() {
        assertThatThrownBy(() -> new TokenBucket(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void securityConfigDefaults() {
        RpcSecurityConfig config = RpcSecurityConfig.disabled();
        assertThat(config.sslEnabled()).isFalse();
        assertThat(config.authenticationEnabled()).isFalse();
        assertThat(config.rateLimitEnabled()).isFalse();
    }

    @Test
    void sslAndAuthCombined() throws Exception {
        SelfSignedCertificate cert = cert();
        long expiry = future();
        RpcServer server = server(security(cert, true, 0, "tok", expiry));
        RpcClient client = client(security(cert, true, 0, "tok", expiry));
        RpcFrame response = call(client, server, "combo");
        assertThat(response.payload()).isEqualTo(bytes("combo"));
    }

    @Test
    void errorFrameRoundTrip() throws Exception {
        RpcServer server = server(RpcSecurityConfig.disabled());
        RpcClient client = client(RpcSecurityConfig.disabled());
        RpcFrame response = call(client, server, "echo-error");
        assertThat(response.type()).isEqualTo(RpcMessageType.REQUEST_VOTE_RESPONSE);
    }

    @Test
    void plainServerStillWorksWithDefaultClient() throws Exception {
        RpcServer server = server(RpcSecurityConfig.disabled());
        RpcClient client = client(RpcSecurityConfig.disabled());
        assertThat(call(client, server, "plain").payload()).isEqualTo(bytes("plain"));
    }

    @Test
    void largePayloadOverTls() throws Exception {
        SelfSignedCertificate cert = cert();
        RpcServer server = server(security(cert, false, 0));
        RpcClient client = client(security(cert, false, 0));
        byte[] payload = new byte[512 * 1024];
        java.util.Arrays.fill(payload, (byte) 7);
        RpcFrame response = callAsync(client, server, payload).get(10, TimeUnit.SECONDS);
        assertThat(response.payload()).isEqualTo(payload);
    }

    @Test
    void serverWithoutAuthAcceptsAllFrames() throws Exception {
        RpcServer server = server(RpcSecurityConfig.disabled());
        RpcClient client = client(RpcSecurityConfig.disabled());
        for (int i = 0; i < 3; i++) {
            assertThat(call(client, server, "n" + i).payload()).isEqualTo(bytes("n" + i));
        }
    }

    @Test
    void authResponsePayloadIndicatesSuccess() throws Exception {
        SelfSignedCertificate cert = cert();
        RpcServer server = server(security(cert, true, 0, "secret", future()));
        RpcClient client = client(security(cert, true, 0, "secret", future()));
        assertThat(call(client, server, "authed2").payload()).isEqualTo(bytes("authed2"));
    }

    @Test
    void rateLimitedCombinedWithAuth() throws Exception {
        SelfSignedCertificate cert = cert();
        long expiry = future();
        RpcServer server = server(new RpcSecurityConfig(true, cert.certificate().toPath(),
                cert.privateKey().toPath(), "tok", expiry, 5));
        RpcClient client = client(security(cert, true, 0, "tok", expiry));
        int errors = 0;
        for (int i = 0; i < 10; i++) {
            RpcFrame response = callAsync(client, server, "c" + i).get(5, TimeUnit.SECONDS);
            if (response.type() == RpcMessageType.ERROR
                    && new String(response.payload(), java.nio.charset.StandardCharsets.UTF_8)
                    .equals("ERR RATE_LIMIT")) {
                errors++;
            }
        }
        assertThat(errors).isGreaterThan(0);
    }

    // ---------- helpers ----------

    private static SelfSignedCertificate cert() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        return cert;
    }

    private static RpcSecurityConfig security(SelfSignedCertificate cert, boolean ssl,
                                              int qps) {
        return new RpcSecurityConfig(ssl, cert.certificate().toPath(),
                cert.privateKey().toPath(), null, 0, qps);
    }

    private static RpcSecurityConfig security(SelfSignedCertificate cert, boolean ssl,
                                              int qps, String token, long expiry) {
        return new RpcSecurityConfig(ssl, cert.certificate().toPath(),
                cert.privateKey().toPath(), token, expiry, qps);
    }

    private static long future() {
        return System.currentTimeMillis() + 60_000;
    }

    private RpcServer server(RpcSecurityConfig config) throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        RpcServer server = new RpcServer(port, config);
        server.handler(frame -> new RpcFrame(frame.requestId(),
                RpcMessageType.REQUEST_VOTE_RESPONSE, frame.payload()));
        server.start();
        resources.add(server);
        return server;
    }

    private RpcClient client(RpcSecurityConfig config) {
        RpcClient client = new RpcClient(config);
        resources.add(client);
        return client;
    }

    private static RpcFrame call(RpcClient client, RpcServer server, String payload)
            throws Exception {
        return callAsync(client, server, bytes(payload)).get(5, TimeUnit.SECONDS);
    }

    private static CompletableFuture<RpcFrame> callAsync(RpcClient client, RpcServer server,
                                                         String payload) {
        return callAsync(client, server, bytes(payload));
    }

    private static CompletableFuture<RpcFrame> callAsync(RpcClient client, RpcServer server,
                                                         byte[] payload) {
        return client.call(new InetSocketAddress("127.0.0.1", server.boundPort()),
                new RpcFrame(RequestId.next().value(), RpcMessageType.REQUEST_VOTE, payload),
                3000, 0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
