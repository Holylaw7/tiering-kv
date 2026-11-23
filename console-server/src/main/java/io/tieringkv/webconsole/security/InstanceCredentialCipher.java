package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.config.ConsoleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 实例纳管凭据静态加解密器（M3-B3）：配置 {@code console.security.encryption-key} 时，
 * 使用 AES-256-GCM 加密落库（前缀 {@code enc:v1:}）；未配置或遇到历史明文行时平滑兼容。
 */
@Component
public class InstanceCredentialCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public InstanceCredentialCipher(ConsoleProperties properties) {
        this(properties.getSecurity() == null ? null : properties.getSecurity().getEncryptionKey());
    }

    public InstanceCredentialCipher(String masterSecret) {
        this.key = (masterSecret == null || masterSecret.isBlank()) ? null : deriveAes256Key(masterSecret);
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || key == null || plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("实例口令加密失败", ex);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("检测到加密口令但未配置 console.security.encryption-key");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_BYTES) {
                throw new IllegalStateException("加密口令格式损坏");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("实例口令解密失败，请核对加密主密钥", ex);
        }
    }

    private static SecretKeySpec deriveAes256Key(String masterSecret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(masterSecret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
