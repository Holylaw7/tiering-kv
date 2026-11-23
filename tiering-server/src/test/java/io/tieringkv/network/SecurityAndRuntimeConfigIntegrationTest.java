package io.tieringkv.network;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RuntimeConfigBridge;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 里程碑 M1（A2 TLS/AUTH/ACL + A3 CONFIG SET 真接线与连接保护）集成测试。
 */
class SecurityAndRuntimeConfigIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void passwordlessDefaultAllowsCommandsAndAuthBarrierProtectsWhenEnabled() throws Exception {
        MemTable memTable = MemTable.create();
        AclPolicy acl = new AclPolicy();
        CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                () -> "# Server\r\n", java.util.Map.of(), null, null, null,
                acl, RuntimeConfigBridge.standaloneDefaults());
        try (KeyShardExecutor executor = new KeyShardExecutor(4, "m1-sec");
             TieringKvServer server = new TieringKvServer(
                     new ServerConfig("127.0.0.1", 0),
                     new CommandEngine(registry, memTable, executor, null, acl),
                     new MetricsRegistry())) {
            server.start();
            // 默认免密兼容
            try (RespConn s = RespConn.plain(server.boundPort())) {
                assertThat(s.cmdLine("PING")).isEqualTo("+PONG");
            }

            // 运行期启用 requirepass，未认证连接禁止读写，流水线 AUTH 严格保序
            acl.configureRequirePass("topsecret");
            try (RespConn unauth = RespConn.plain(server.boundPort())) {
                assertThat(unauth.cmdLine("PING")).startsWith("-NOAUTH");
                assertThat(unauth.cmdLine("HELLO", "3")).startsWith("-NOAUTH");
            }

            try (RespConn piped = RespConn.plain(server.boundPort())) {
                piped.sendRaw(resp("AUTH", "topsecret")
                        + resp("SET", "k:pipe", "v1")
                        + resp("GET", "k:pipe"));
                assertThat(piped.readLine()).isEqualTo("+OK");
                assertThat(piped.readLine()).isEqualTo("+OK");
                assertThat(piped.readLine()).isEqualTo("$2");
                assertThat(piped.readLine()).isEqualTo("v1");

                // 创建只读 ACL 用户验证 HELLO AUTH + NOPERM + EXEC 事务二次鉴权
                assertThat(piped.cmdLine(
                        "ACL", "SETUSER", "rouser", "on", ">ro123", "~ro:*", "-@all", "+@read"))
                        .isEqualTo("+OK");
            }

            try (RespConn roSocket = RespConn.plain(server.boundPort())) {
                assertThat(roSocket.cmdLine("HELLO", "3", "AUTH", "rouser", "ro123")).isEqualTo("%4");
                for (int i = 0; i < 8; i++) {
                    roSocket.readLine();
                    roSocket.readLine();
                }

                assertThat(roSocket.cmdLine("GET", "ro:1")).isEqualTo("_");
                assertThat(roSocket.cmdLine("SET", "ro:1", "forbidden")).startsWith("-NOPERM");
                assertThat(roSocket.cmdLine("GET", "other:1")).startsWith("-NOPERM");
            }
        }
    }

    @Test
    void execRevalidatesAclIfPermissionsRevokedMidTransaction() throws Exception {
        MemTable memTable = MemTable.create();
        AclPolicy acl = new AclPolicy();
        acl.configureRequirePass("adminpw");
        acl.setUser("writer", java.util.List.of("on", ">pw", "~*", "+@all"));
        CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                () -> "# Server\r\n", java.util.Map.of(), null, null, null,
                acl, RuntimeConfigBridge.standaloneDefaults());
        try (KeyShardExecutor executor = new KeyShardExecutor(2, "m1-txn");
             TieringKvServer server = new TieringKvServer(
                     new ServerConfig("127.0.0.1", 0),
                     new CommandEngine(registry, memTable, executor, null, acl),
                     new MetricsRegistry());
             RespConn client = RespConn.plain(startAndPort(server))) {
            assertThat(client.cmdLine("AUTH", "writer", "pw")).isEqualTo("+OK");
            assertThat(client.cmdLine("MULTI")).isEqualTo("+OK");
            assertThat(client.cmdLine("SET", "k:tx", "val")).isEqualTo("+QUEUED");

            // 事务提交前撤销写权限，EXEC 回放阶段仍应拦截
            acl.setUser("writer", java.util.List.of("-@write"));
            assertThat(client.cmdLine("EXEC")).isEqualTo("*1");
            assertThat(client.readLine()).startsWith("-NOPERM");
            assertThat(memTable.get("k:tx".getBytes(StandardCharsets.UTF_8))).isNull();
        }
    }

    @Test
    void tlsServerAcceptsEncryptedRespAndAuth() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("127.0.0.1");
        ServerConfig tlsServerConfig = new ServerConfig(
                "127.0.0.1", 0, 100, 0, "tlssecret",
                new ServerConfig.TlsConfig(true, cert.certificate().toPath(), cert.privateKey().toPath(), null, false));
        AclPolicy acl = new AclPolicy();
        acl.configureRequirePass("tlssecret");
        CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                () -> "# Server\r\n", java.util.Map.of(), null, null, null,
                acl, RuntimeConfigBridge.standaloneDefaults());

        SSLContext clientSsl = trustAllClientContext();
        SSLSocketFactory factory = clientSsl.getSocketFactory();

        try (KeyShardExecutor executor = new KeyShardExecutor(2, "m1-tls");
             TieringKvServer server = new TieringKvServer(
                     tlsServerConfig,
                     new CommandEngine(registry, MemTable.create(), executor, null, acl),
                     new MetricsRegistry());
             RespConn sslSocket = new RespConn(factory.createSocket("127.0.0.1", startAndPort(server)))) {
            assertThat(sslSocket.cmdLine("PING")).startsWith("-NOAUTH");
            assertThat(sslSocket.cmdLine("AUTH", "tlssecret")).isEqualTo("+OK");
            assertThat(sslSocket.cmdLine("SET", "tls:key", "ok")).isEqualTo("+OK");
        }
    }

    @Test
    void configSetWiresRealMemoryWalMaxClientsAndTimeout() throws Exception {
        MemoryManager memoryManager = new MemoryManager(1024 * 1024, 0);
        MemTable memTable = MemTable.create(memoryManager);
        WALManager walManager = new WALManager(new WALConfig(
                tempDir.resolve("wal"), 1024 * 1024, WALConfig.FsyncPolicy.EVERY_SEC));
        AclPolicy acl = new AclPolicy();
        TieringKvServer.RuntimeOptions options = new TieringKvServer.RuntimeOptions(100, 0);
        RuntimeConfigBridge bridge = RuntimeConfigBridge.builder()
                .maxMemory(memoryManager::maxBytes, memoryManager::setMaxBytes)
                .appendFsync(walManager::fsyncPolicy, walManager::setFsyncPolicy)
                .maxClients(options::maxClients, options::setMaxClients)
                .timeout(options::idleTimeoutSeconds, options::setIdleTimeoutSeconds)
                .requirePass(acl::requiresAuthForAnonymous, acl::configureRequirePass)
                .build();
        CommandRegistry registry = CommandRegistry.createDefaultWithRuntime(
                () -> "# Server\r\n", java.util.Map.of(), null, null, null,
                acl, bridge);

        try (walManager;
             KeyShardExecutor executor = new KeyShardExecutor(2, "m1-cfg");
             TieringKvServer server = new TieringKvServer(
                     new ServerConfig("127.0.0.1", 0),
                     new CommandEngine(registry, memTable, executor, memoryManager::isOverLimit, acl),
                     new MetricsRegistry(),
                     options)) {
            int port = startAndPort(server);
            try (RespConn admin = RespConn.plain(port)) {
                // 1. maxmemory 真生效 -> OOM 写拒绝
                assertThat(admin.cmdLine("SET", "seed", "1234567890123456")).isEqualTo("+OK");
                assertThat(admin.cmdLine("CONFIG", "SET", "maxmemory", "1")).isEqualTo("+OK");
                assertThat(admin.cmdLine("SET", "blocked", "val")).startsWith("-OOM");
                assertThat(admin.cmdLine("CONFIG", "SET", "maxmemory", "1048576")).isEqualTo("+OK");
                assertThat(admin.cmdLine("SET", "allowed", "val")).isEqualTo("+OK");

                // 2. appendfsync 真生效 -> 切换 WALManager 策略
                assertThat(admin.cmdLine("CONFIG", "SET", "appendfsync", "always")).isEqualTo("+OK");
                assertThat(walManager.fsyncPolicy()).isEqualTo(WALConfig.FsyncPolicy.ALWAYS);
                assertThat(admin.cmdLine("CONFIG", "SET", "appendfsync", "everysec")).isEqualTo("+OK");
                assertThat(walManager.fsyncPolicy()).isEqualTo(WALConfig.FsyncPolicy.EVERY_SEC);

                // 3. requirepass 生效后未认证连接需先 AUTH，且 CONFIG GET 不泄露明文
                assertThat(admin.cmdLine("CONFIG", "SET", "requirepass", "pw123")).isEqualTo("+OK");
                assertThat(admin.cmdLine("CONFIG", "GET", "requirepass")).startsWith("-NOAUTH");
                assertThat(admin.cmdLine("AUTH", "pw123")).isEqualTo("+OK");
                assertThat(admin.cmdLine("CONFIG", "GET", "requirepass")).isEqualTo("*2");
                assertThat(admin.readLine()).isEqualTo("$11");
                assertThat(admin.readLine()).isEqualTo("requirepass");
                assertThat(admin.readLine()).isEqualTo("$8");
                assertThat(admin.readLine()).isEqualTo("********");
                assertThat(admin.cmdLine("CONFIG", "SET", "requirepass", "")).isEqualTo("+OK");

                // 4. maxclients 真生效 -> 第 2 条连接被拒并关闭
                assertThat(admin.cmdLine("CONFIG", "SET", "maxclients", "1")).isEqualTo("+OK");
                try (RespConn overflow = RespConn.plain(port)) {
                    assertThat(overflow.readLine()).contains("max number of clients reached");
                }
                assertThat(admin.cmdLine("CONFIG", "SET", "maxclients", "10")).isEqualTo("+OK");

                // 5. timeout 真生效 -> 已建立空闲连接在读超时后自动断开
                try (RespConn idle = RespConn.plain(port)) {
                    assertThat(idle.cmdLine("PING")).isEqualTo("+PONG");
                    assertThat(admin.cmdLine("CONFIG", "SET", "timeout", "1")).isEqualTo("+OK");
                    assertThat(idle.readRawByte()).isEqualTo(-1);
                }
            }
        }
    }

    private static int startAndPort(TieringKvServer server) throws InterruptedException {
        server.start();
        return server.boundPort();
    }

    private static SSLContext trustAllClientContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return ctx;
    }

    private static String resp(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] bytes = p.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
    }

    private static final class RespConn implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream in;
        private final OutputStream out;

        RespConn(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setSoTimeout(4000);
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        static RespConn plain(int port) throws IOException {
            return new RespConn(new Socket("127.0.0.1", port));
        }

        void sendRaw(String payload) throws IOException {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        String cmdLine(String... parts) throws IOException {
            sendRaw(resp(parts));
            return readLine();
        }

        int readRawByte() throws IOException {
            return in.read();
        }

        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int prev = -1;
            while (true) {
                int b = in.read();
                if (b == -1) {
                    throw new EOFException("connection closed while reading line: " + sb);
                }
                if (b == '\n' && prev == '\r') {
                    sb.setLength(sb.length() - 1);
                    return sb.toString();
                }
                sb.append((char) b);
                prev = b;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
