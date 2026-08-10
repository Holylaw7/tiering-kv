package io.tieringkv.cluster.rpc.security;

import java.nio.file.Path;

/** RPC 安全配置（ADR-0046）：TLS 证书、认证 token 与过期时间、限流 QPS。 */
public record RpcSecurityConfig(
        boolean sslEnabled,
        Path certFile,
        Path keyFile,
        String authToken,
        long authExpiryMillis,
        int rateLimitQps) {

    public static RpcSecurityConfig disabled() {
        return new RpcSecurityConfig(false, null, null, null, 0, 0);
    }

    public boolean authenticationEnabled() {
        return authToken != null && !authToken.isBlank();
    }

    public boolean rateLimitEnabled() {
        return rateLimitQps > 0;
    }
}
