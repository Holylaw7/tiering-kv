package io.tieringkv.cluster.scheduler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 监管级合规证书（ADR-0245）：审计链摘要 + 时间戳 + 签发者 + 签名；
 * 密钥轮换（旧密钥保留验证）+ 外部验证。
 */
public final class RegulatoryComplianceCertificate {

    /** 合规证书。 */
    public record Certificate(String chainDigest, long issuedAt,
                              String issuer, String signature,
                              long keyVersion) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final List<Certificate> certificates =
            new CopyOnWriteArrayList<>();
    private final List<String> revokedKeys =
            new CopyOnWriteArrayList<>();
    private final Map<Long, byte[]> versionKeys = new ConcurrentHashMap<>();
    private final Object keyLock = new Object();
    private volatile long keyVersion = 1;

    public RegulatoryComplianceCertificate() {
        versionKeys.put(1L, generateKey());
    }

    /** 签发证书：chainDigest 来自审计链摘要。 */
    public Certificate issue(String chainDigest, String issuer) {
        if (chainDigest == null || chainDigest.isBlank()
                || issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "chainDigest and issuer required");
        }
        long issuedAt = System.currentTimeMillis();
        long ver;
        byte[] key;
        synchronized (keyLock) {
            ver = keyVersion;
            key = versionKeys.get(ver);
        }
        String signature = sign(key, chainDigest + "|" + issuedAt
                + "|" + issuer + "|" + ver);
        Certificate certificate = new Certificate(chainDigest,
                issuedAt, issuer, signature, ver);
        certificates.add(certificate);
        return certificate;
    }

    /** 密钥轮换：版本 + 1，旧密钥保留在 versionKeys（验证仍可用）。 */
    public void rotateKey() {
        synchronized (keyLock) {
            revokedKeys.add("key-v" + keyVersion);
            long next = keyVersion + 1;
            versionKeys.put(next, generateKey());
            keyVersion = next;
        }
    }

    /** 外部验证：使用对应密钥版本重算 HMAC-SHA256 + 常时间比对。 */
    public boolean verify(Certificate certificate) {
        if (certificate == null || certificate.issuedAt() < 0
                || certificate.keyVersion() < 1
                || certificate.keyVersion() > keyVersion
                || certificate.signature() == null) {
            return false;
        }
        byte[] key = versionKeys.get(certificate.keyVersion());
        if (key == null) {
            return false;
        }
        String expected = sign(key, certificate.chainDigest()
                + "|" + certificate.issuedAt()
                + "|" + certificate.issuer()
                + "|" + certificate.keyVersion());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                certificate.signature().getBytes(StandardCharsets.UTF_8));
    }

    public List<Certificate> certificates() {
        return List.copyOf(certificates);
    }

    public List<String> revokedKeys() {
        return List.copyOf(revokedKeys);
    }

    public long keyVersion() {
        return keyVersion;
    }

    private static byte[] generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String sign(byte[] secret, String entry) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal(entry.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("signing failed", e);
        }
    }
}
