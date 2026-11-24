package io.tieringkv.webconsole.resp;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.service.AuditService;
import io.tieringkv.webconsole.service.InstanceService;
import io.tieringkv.webconsole.service.RespGateway;
import io.tieringkv.webconsole.web.dto.InstanceRequest;
import io.tieringkv.webconsole.web.dto.InstanceView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** O2-B：控制面至数据面 TLS 加密连接、自定义 PEM CA 信任校验与配置校验。 */
class RespClientTlsTest {

    @TempDir
    Path tempDir;

    private record CertBundle(Path p12Path, String password, String caPem) {
    }

    private CertBundle generateSelfSignedCert() throws Exception {
        Path p12 = tempDir.resolve("server.p12");
        Path pem = tempDir.resolve("ca.pem");
        String pwd = "changeit123";
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

        Process gen = new ProcessBuilder(
                keytool, "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "2",
                "-storetype", "PKCS12",
                "-keystore", p12.toString(),
                "-storepass", pwd,
                "-dname", "CN=localhost",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1"
        ).redirectErrorStream(true).start();
        assertThat(gen.waitFor()).as("keytool -genkeypair failed: %s",
                new String(gen.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(0);

        Process export = new ProcessBuilder(
                keytool, "-exportcert", "-rfc",
                "-alias", "server",
                "-keystore", p12.toString(),
                "-storepass", pwd,
                "-file", pem.toString()
        ).redirectErrorStream(true).start();
        assertThat(export.waitFor()).as("keytool -exportcert failed: %s",
                new String(export.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(0);

        return new CertBundle(p12, pwd, Files.readString(pem, StandardCharsets.UTF_8));
    }

    private SSLServerSocket openTlsServer(CertBundle bundle) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(bundle.p12Path())) {
            ks.load(in, bundle.password().toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, bundle.password().toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0);
    }

    @Test
    void connectsOverTlsWithCustomCaAndExecutesPipeline() throws Exception {
        CertBundle bundle = generateSelfSignedCert();
        try (SSLServerSocket server = openTlsServer(bundle)) {
            Thread loop = Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try (SSLSocket s = (SSLSocket) server.accept()) {
                        s.startHandshake();
                        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                        OutputStream out = s.getOutputStream();
                        while (true) {
                            RespCodec.read(in);
                            out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException ignored) {
                        // 客户端关闭或握手失败
                    }
                }
            });

            // 1. 合法自定义 CA + 主机名校验通过（SAN 含 IP:127.0.0.1）
            RespClient.TlsOptions trusted = new RespClient.TlsOptions(true, true, bundle.caPem());
            try (RespClient client = RespClient.open("127.0.0.1", server.getLocalPort(), 3000, 3000, trusted)) {
                RespReply single = client.execute("PING");
                assertThat(((RespReply.Simple) single).value()).isEqualTo("PONG");

                List<RespReply> batch = client.executeBatch(List.of(
                        RespClient.commandBytes("PING"),
                        RespClient.commandBytes("PING"),
                        RespClient.commandBytes("PING")));
                assertThat(batch).hasSize(3);
                assertThat(batch).allSatisfy(r -> assertThat(((RespReply.Simple) r).value()).isEqualTo("PONG"));
            }

            // 2. 未提供自定义 CA 时默认 TrustStore 必须拒绝自签名证书
            RespClient.TlsOptions untrusted = new RespClient.TlsOptions(true, true, null);
            assertThatThrownBy(() -> RespClient.open("127.0.0.1", server.getLocalPort(), 3000, 3000, untrusted))
                    .isInstanceOf(SSLHandshakeException.class);

            server.close();
            loop.join(2000);
        }
    }

    @Test
    void instanceServiceValidatesPemAndExposesOnlyBooleanFlags() throws Exception {
        CertBundle bundle = generateSelfSignedCert();
        DbInstanceRepository repo = mock(DbInstanceRepository.class);
        when(repo.existsByHostAndPort(any(), any(Integer.class))).thenReturn(false);
        when(repo.findByName(any())).thenReturn(Optional.empty());
        when(repo.save(any(DbInstance.class))).thenAnswer(inv -> {
            DbInstance d = inv.getArgument(0);
            if (d.getId() == null) d.setId(10L);
            return d;
        });

        InstanceService svc = new InstanceService(repo, mock(RespGateway.class), mock(AuditService.class));

        // 非法 PEM 必须前置校验拒绝（HTTP 400）
        InstanceRequest badReq = new InstanceRequest(
                "tls-bad", "127.0.0.1", 6380, null, null, true, true, true, "NOT-A-PEM");
        assertThatThrownBy(() -> svc.create(badReq))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TLS CA");

        // 合法 PEM 入库成功且视图不泄漏原始 PEM 文本
        InstanceRequest okReq = new InstanceRequest(
                "tls-ok", "127.0.0.1", 6380, null, "secure", true, true, true, bundle.caPem());
        InstanceView view = svc.create(okReq);
        assertThat(view.tlsEnabled()).isTrue();
        assertThat(view.tlsVerifyHostname()).isTrue();
        assertThat(view.hasTlsCaCert()).isTrue();
    }
}
