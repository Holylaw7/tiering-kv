package io.tieringkv.cluster.rpc.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;

/**
 * HMAC-SHA256 认证 token（ADR-0051）：
 * clientId|timestamp|nonce|base64(HMAC(clientId|timestamp|nonce, key))。
 * 密钥表支持轮换：签发用第一个 key，校验时任一匹配。
 */
public final class HmacToken {

    private static final String SEP = "|";
    private static final String ALGORITHM = "HmacSHA256";

    private HmacToken() {
    }

    public static String issue(String clientId, long timestamp, String nonce, String key) {
        return clientId + SEP + timestamp + SEP + nonce + SEP
                + Base64.getEncoder().encodeToString(sign(clientId, timestamp, nonce, key));
    }

    /** 校验签名 + 时间窗口 + 防重放。 */
    public static boolean verify(String token, HmacConfig config, NonceCache nonces, long now) {
        String[] parts = token.split("\\|", -1);
        if (parts.length != 4) {
            return false;
        }
        String clientId = parts[0];
        long timestamp;
        try {
            timestamp = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        String nonce = parts[2];
        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        boolean signatureValid = false;
        for (String key : config.keys()) {
            byte[] expected = sign(clientId, timestamp, nonce, key);
            if (java.security.MessageDigest.isEqual(expected, provided)) {
                signatureValid = true;
                break;
            }
        }
        if (!signatureValid) {
            return false;
        }
        return nonces.tryConsume(clientId, nonce, timestamp, config.windowMillis(), now);
    }

    private static byte[] sign(String clientId, long timestamp, String nonce, String key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal((clientId + SEP + timestamp + SEP + nonce)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }
}
