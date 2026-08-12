package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** 签名证明（ADR-0182）：HMAC-SHA256 节点签名。 */
public final class SignedAttestation {

    private SignedAttestation() {
    }

    /** 签名证明：证明 + 签名。 */
    public record Signed(Attestation attestation,
                         String signature) {
    }

    public static Signed sign(Attestation attestation,
                              byte[] key) {
        if (attestation == null) {
            throw new IllegalArgumentException(
                    "attestation required");
        }
        requireKey(key);
        return new Signed(attestation, hmac(key,
                payload(attestation)));
    }

    static String payload(Attestation attestation) {
        return attestation.index() + "|"
                + attestation.regulation() + "|"
                + attestation.versionId() + "|"
                + attestation.violations() + "|"
                + attestation.prevHash() + "|"
                + attestation.hash() + "|"
                + attestation.timestampMillis();
    }

    static String hmac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] bytes = mac.doFinal(
                    payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "HMAC unavailable", e);
        }
    }

    static void requireKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException(
                    "key required");
        }
    }
}
