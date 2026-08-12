package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;
import io.tieringkv.compliance.SignedAttestation.Signed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 签名验证（ADR-0182）：公钥校验 + 篡改检测。 */
public final class SignatureVerifier {

    public boolean verify(Signed signed, byte[] key) {
        if (signed == null) {
            throw new IllegalArgumentException(
                    "signed attestation required");
        }
        SignedAttestation.requireKey(key);
        String expected = SignedAttestation.hmac(key,
                SignedAttestation.payload(signed.attestation()));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signed.signature().getBytes(
                        StandardCharsets.UTF_8));
    }

    public boolean verify(Attestation attestation, String signature,
                          byte[] key) {
        if (attestation == null || signature == null
                || signature.isBlank()) {
            throw new IllegalArgumentException(
                    "attestation and signature required");
        }
        return verify(new Signed(attestation, signature), key);
    }
}
