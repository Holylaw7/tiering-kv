package io.tieringkv.config;

import java.nio.file.Path;

/**
 * 服务端启动配置：网络监听地址、最大连接数、空闲读超时、默认密码与可选 TLS。
 */
public record ServerConfig(
        String host,
        int port,
        int maxClients,
        int idleTimeoutSeconds,
        String password,
        TlsConfig tls) {

    public record TlsConfig(
            boolean enabled,
            Path certPath,
            Path keyPath,
            Path caPath,
            boolean requireClientAuth) {

        public static TlsConfig disabled() {
            return new TlsConfig(false, null, null, null, false);
        }
    }

    public ServerConfig(String host, int port) {
        this(host, port, 10_000, 0, null, TlsConfig.disabled());
    }

    public ServerConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (maxClients < 0) {
            throw new IllegalArgumentException("maxClients must be non-negative");
        }
        if (idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must be non-negative");
        }
        if (tls == null) {
            tls = TlsConfig.disabled();
        }
        if (tls.enabled() && (tls.certPath() == null || tls.keyPath() == null)) {
            throw new IllegalArgumentException("tls enabled requires certPath and keyPath");
        }
    }
}
