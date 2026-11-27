package io.tieringkv.webconsole.security;

import com.sun.net.httpserver.HttpServer;
import io.tieringkv.webconsole.domain.AlertChannel;
import io.tieringkv.webconsole.repository.AlertChannelRepository;
import io.tieringkv.webconsole.service.AlertWebhookDispatcher;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wave 1 S1 专项安全验证：
 * 真实套接字层面验证 IP 固定连接（零二次 DNS 查询）、HTTP 3xx 重定向严格禁止，
 * 以及 HTTPS 场景下的 TLS SNI、原始 Host 头与 X.509 证书主机名校验。
 */
class PinnedWebhookSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void connectsDirectlyToPinnedIpWithSingleDnsLookupAndPreservesOriginalHostHeader() throws Exception {
        AtomicReference<String> receivedHost = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedSig = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alerts", exchange -> {
            receivedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            receivedPath.set(exchange.getRequestURI().toString());
            receivedSig.set(exchange.getRequestHeaders().getFirst("X-TieringKV-Signature"));
            exchange.getRequestBody().readAllBytes();
            byte[] ok = "accepted".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AtomicInteger dnsLookups = new AtomicInteger();
            // 域名在真实 DNS 中根本不存在：若 HTTP 客户端擅自二次解析将直接失败；
            // 且第 2 次调用会投毒返回 169.254.169.254，反向证明仅执行了 1 次 DNS 解析并直连固定 IP
            OutboundUrlGuard.HostResolver singleShotResolver = host -> {
                int n = dnsLookups.incrementAndGet();
                if ("pinned-hook.nonexistent.invalid".equals(host) && n == 1) {
                    return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                }
                return new InetAddress[]{InetAddress.getByName("169.254.169.254")};
            };
            OutboundUrlGuard guard = new OutboundUrlGuard(true, List.of("127.0.0.1/32"), singleShotResolver);
            InstanceCredentialCipher cipher = new InstanceCredentialCipher((String) null);
            AlertWebhookDispatcher dispatcher = new AlertWebhookDispatcher(
                    mock(AlertChannelRepository.class), cipher, new ObjectMapper(),
                    Runnable::run, guard, new PinnedWebhookHttpClient());

            AlertChannel channel = new AlertChannel();
            channel.setId(1L);
            channel.setName("pinned-http");
            channel.setType("WEBHOOK");
            channel.setUrl("http://pinned-hook.nonexistent.invalid:" + port + "/alerts?env=prod");
            channel.setSecret("sig-secret-1");
            channel.setEnabled(true);

            WebhookTestResult result = dispatcher.sendTest(channel);
            assertThat(result.delivered()).isTrue();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(dnsLookups.get()).isEqualTo(1);
            assertThat(receivedHost.get()).isEqualTo("pinned-hook.nonexistent.invalid:" + port);
            assertThat(receivedPath.get()).isEqualTo("/alerts?env=prod");
            assertThat(receivedSig.get()).startsWith("sha256=");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void strictlyBlocksHttp302RedirectWithoutContactingRedirectTarget() throws Exception {
        AtomicInteger secretTargetHits = new AtomicInteger();
        HttpServer secretServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secretServer.createContext("/internal-secret", exchange -> {
            secretTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        secretServer.start();

        HttpServer redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int secretPort = secretServer.getAddress().getPort();
        redirectServer.createContext("/redirect", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + secretPort + "/internal-secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirectServer.start();

        try {
            OutboundUrlGuard guard = new OutboundUrlGuard(
                    true, List.of("127.0.0.1/32"), InetAddress::getAllByName);
            AlertWebhookDispatcher dispatcher = new AlertWebhookDispatcher(
                    mock(AlertChannelRepository.class), new InstanceCredentialCipher((String) null),
                    new ObjectMapper(), Runnable::run, guard, new PinnedWebhookHttpClient());

            AlertChannel channel = new AlertChannel();
            channel.setId(2L);
            channel.setName("redirect-hook");
            channel.setType("WEBHOOK");
            channel.setUrl("http://127.0.0.1:" + redirectServer.getAddress().getPort() + "/redirect");
            channel.setEnabled(true);

            WebhookTestResult result = dispatcher.sendTest(channel);
            assertThat(result.delivered()).isFalse();
            assertThat(result.statusCode()).isEqualTo(302);
            assertThat(result.message()).contains("重定向已禁止");
            assertThat(secretTargetHits.get()).isZero();
        } finally {
            redirectServer.stop(0);
            secretServer.stop(0);
        }
    }

    @Test
    void pinnedHttpsPreservesTlsSniHostHeaderAndStrictX509HostnameVerification() throws Exception {
        // 证书 SAN 仅包含 DNS:webhook.secure.test（不包含 IP:127.0.0.1），
        // 严格证明虽然 TCP 直连 127.0.0.1，但 SNI 与 X.509 主机名校验均按原始域名正确生效
        SSLContext sslContext = buildTestSslContext("webhook.secure.test");
        AtomicReference<String> observedSni = new AtomicReference<>();
        AtomicReference<String> observedHostHeader = new AtomicReference<>();

        try (SSLServerSocket server = (SSLServerSocket) sslContext.getServerSocketFactory()
                .createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread acceptLoop = Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try (SSLSocket socket = (SSLSocket) server.accept()) {
                        socket.startHandshake();
                        if (socket.getSession() instanceof ExtendedSSLSession ext) {
                            for (SNIServerName name : ext.getRequestedServerNames()) {
                                if (name instanceof SNIHostName hostName) {
                                    observedSni.set(hostName.getAsciiName());
                                }
                            }
                        }
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                        String line;
                        int contentLength = 0;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("host:")) {
                                observedHostHeader.set(line.substring("host:".length()).trim());
                            } else if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                            }
                        }
                        for (int i = 0; i < contentLength; i++) {
                            if (reader.read() == -1) {
                                break;
                            }
                        }
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                .getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch (IOException ignored) {
                        // 主机名不匹配的负向用例握手会正常中止
                    }
                }
            });

            AtomicInteger dnsLookups = new AtomicInteger();
            OutboundUrlGuard.HostResolver resolver = host -> {
                dnsLookups.incrementAndGet();
                return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            };
            OutboundUrlGuard guard = new OutboundUrlGuard(true, List.of("127.0.0.1/32"), resolver);
            PinnedWebhookHttpClient pinnedClient = new PinnedWebhookHttpClient(sslContext);
            AlertWebhookDispatcher dispatcher = new AlertWebhookDispatcher(
                    mock(AlertChannelRepository.class), new InstanceCredentialCipher((String) null),
                    new ObjectMapper(), Runnable::run, guard, pinnedClient);

            // 1. 正向：证书 SAN 匹配 webhook.secure.test，单次解析 + 直连 127.0.0.1 + SNI/Host 全正确
            AlertChannel okChannel = new AlertChannel();
            okChannel.setId(3L);
            okChannel.setName("tls-ok");
            okChannel.setType("WEBHOOK");
            okChannel.setUrl("https://webhook.secure.test:" + port + "/notify");
            okChannel.setEnabled(true);

            WebhookTestResult okRes = dispatcher.sendTest(okChannel);
            assertThat(okRes.delivered()).isTrue();
            assertThat(okRes.statusCode()).isEqualTo(200);
            assertThat(dnsLookups.get()).isEqualTo(1);
            assertThat(observedSni.get()).isEqualTo("webhook.secure.test");
            assertThat(observedHostHeader.get()).isEqualTo("webhook.secure.test:" + port);

            // 2. 负向：域名与证书 SAN 不匹配，必须因 HTTPS 证书主机名校验失败被拒绝
            AlertChannel mismatchChannel = new AlertChannel();
            mismatchChannel.setId(4L);
            mismatchChannel.setName("tls-mismatch");
            mismatchChannel.setType("WEBHOOK");
            mismatchChannel.setUrl("https://wrong-host.secure.test:" + port + "/notify");
            mismatchChannel.setEnabled(true);

            WebhookTestResult failRes = dispatcher.sendTest(mismatchChannel);
            assertThat(failRes.delivered()).isFalse();
            assertThat(failRes.statusCode()).isZero();
            assertThat(failRes.message()).contains("SSLHandshakeException");

            server.close();
            acceptLoop.join(2000);
        }
    }

    private SSLContext buildTestSslContext(String dnsSan) throws Exception {
        Path p12 = tempDir.resolve("webhook-tls.p12");
        String pwd = "changeit123";
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

        Process gen = new ProcessBuilder(
                keytool, "-genkeypair",
                "-alias", "webhook",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "2",
                "-storetype", "PKCS12",
                "-keystore", p12.toString(),
                "-storepass", pwd,
                "-dname", "CN=" + dnsSan,
                "-ext", "SAN=DNS:" + dnsSan
        ).redirectErrorStream(true).start();
        assertThat(gen.waitFor()).as("keytool failed: %s",
                new String(gen.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(0);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            ks.load(in, pwd.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pwd.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}
