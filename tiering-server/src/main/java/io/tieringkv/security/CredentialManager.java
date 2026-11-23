package io.tieringkv.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凭据管理器（ADR-0106）：令牌签发 / 轮换 / 吊销 / 校验，支持过期。
 * 令牌不落盘（配合 Secret 注入），进程内状态可被安全混沌测试验证。
 */
public final class CredentialManager {

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private record Entry(Role role, long expiresAtMillis) {
        boolean expired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    public String issue(Role role, long ttlMillis) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes);
        tokens.put(token, new Entry(role,
                System.currentTimeMillis() + ttlMillis));
        return token;
    }

    public Role validate(String token) {
        return validateAt(token, System.currentTimeMillis());
    }

    public Role validateAt(String token, long nowMillis) {
        if (token == null || token.isBlank()) {
            throw new SecurityException("missing token");
        }
        Entry entry = tokens.get(token);
        if (entry == null) {
            throw new SecurityException("unknown token");
        }
        if (entry.expired(nowMillis)) {
            tokens.remove(token);
            throw new SecurityException("token expired");
        }
        return entry.role();
    }

    public void revoke(String token) {
        tokens.remove(token);
    }

    /** 轮换：吊销旧令牌并签发同角色新令牌（ADR-0106）。 */
    public String rotate(String oldToken, long ttlMillis) {
        Role role = validate(oldToken);
        revoke(oldToken);
        return issue(role, ttlMillis);
    }

    public boolean allows(String token, Permission permission) {
        return validate(token).allows(permission);
    }

    /** 授权校验（ADR-0106）：无权限时抛 SecurityException（审计友好）。 */
    public void require(String token, Permission permission) {
        if (!allows(token, permission)) {
            throw new SecurityException(
                    "permission denied: " + permission);
        }
    }

    public int activeTokenCount() {
        tokens.entrySet().removeIf(entry -> entry.getValue()
                .expired(System.currentTimeMillis()));
        return tokens.size();
    }
}
