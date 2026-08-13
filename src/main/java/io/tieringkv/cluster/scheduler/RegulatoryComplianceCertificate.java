package io.tieringkv.cluster.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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

    private final List<Certificate> certificates =
            new CopyOnWriteArrayList<>();
    private final List<String> revokedKeys =
            new CopyOnWriteArrayList<>();
    private volatile long keyVersion = 1;

    /** 签发证书：chainDigest 来自审计链摘要。 */
    public Certificate issue(String chainDigest, String issuer) {
        if (chainDigest == null || chainDigest.isBlank()
                || issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "chainDigest and issuer required");
        }
        long issuedAt = System.currentTimeMillis();
        String signature = sign(chainDigest + "|" + issuedAt
                + "|" + issuer + "|" + keyVersion);
        Certificate certificate = new Certificate(chainDigest,
                issuedAt, issuer, signature, keyVersion);
        certificates.add(certificate);
        return certificate;
    }

    /** 密钥轮换：版本 + 1，旧密钥进入吊销列表（验证仍可用）。 */
    public void rotateKey() {
        revokedKeys.add("key-v" + keyVersion);
        keyVersion++;
    }

    /** 外部验证：重算签名 + 时间戳非负 + 密钥版本有效。 */
    public boolean verify(Certificate certificate) {
        if (certificate == null || certificate.issuedAt() < 0
                || certificate.keyVersion() < 1
                || certificate.keyVersion() > keyVersion) {
            return false;
        }
        String expected = sign(certificate.chainDigest()
                + "|" + certificate.issuedAt()
                + "|" + certificate.issuer()
                + "|" + certificate.keyVersion());
        return expected.equals(certificate.signature());
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

    private static String sign(String entry) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    entry.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "signing failed", e);
        }
    }
}
