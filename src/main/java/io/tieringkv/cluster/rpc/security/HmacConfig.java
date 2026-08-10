package io.tieringkv.cluster.rpc.security;

import java.util.List;

/** HMAC 认证配置（ADR-0051）：clientId + 轮换密钥表 + 时间窗口。 */
public record HmacConfig(
        String clientId,
        List<String> keys,
        long windowMillis) {

    public HmacConfig {
        keys = List.copyOf(keys);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("at least one key required");
        }
    }

    public static HmacConfig single(String clientId, String key) {
        return new HmacConfig(clientId, List.of(key), 30_000);
    }
}
