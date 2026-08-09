package io.tieringkv.config;

/** 服务端启动配置（Phase 1 最小集；完整配置加载在 Phase 10）。 */
public record ServerConfig(String host, int port) {

    public ServerConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }
}
