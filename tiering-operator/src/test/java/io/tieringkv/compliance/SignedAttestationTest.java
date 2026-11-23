package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;
import io.tieringkv.compliance.SignedAttestation.Signed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 签名证明（ADR-0182）：HMAC 签名/验证/篡改检测。 */
class SignedAttestationTest {

    private final SignatureVerifier verifier =
            new SignatureVerifier();

    @Test
    void signAndVerify() {
        byte[] key = key("secret");
        Attestation attestation = attestation(0);
        Signed signed = SignedAttestation.sign(attestation, key);
        assertThat(signed.signature()).hasSize(64);
        assertThat(verifier.verify(signed, key)).isTrue();
    }

    @Test
    void wrongKeyRejected() {
        Attestation attestation = attestation(0);
        Signed signed = SignedAttestation.sign(attestation,
                key("secret"));
        assertThat(verifier.verify(signed, key("other"))).isFalse();
    }

    @Test
    void tamperedAttestationRejected() {
        byte[] key = key("secret");
        Attestation original = attestation(1);
        Signed signed = SignedAttestation.sign(original, key);
        Attestation tampered = new Attestation(original.index(),
                original.regulation(), original.versionId(), 99,
                original.prevHash(), original.hash(),
                original.timestampMillis());
        assertThat(verifier.verify(
                new Signed(tampered, signed.signature()), key))
                .isFalse();
    }

    @Test
    void emptyKeyRejected() {
        assertThatThrownBy(() -> SignedAttestation.sign(
                attestation(0), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier.verify(
                new Signed(attestation(0), "sig"), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullKeyRejected() {
        assertThatThrownBy(() -> SignedAttestation.sign(
                attestation(0), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAttestationRejected() {
        assertThatThrownBy(() -> SignedAttestation.sign(
                null, key("k")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullSignedRejected() {
        assertThatThrownBy(() -> verifier.verify(null, key("k")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullSignatureRejected() {
        assertThatThrownBy(() -> verifier.verify(attestation(0),
                null, key("k")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyByFields() {
        byte[] key = key("secret");
        Attestation attestation = attestation(2);
        String signature = SignedAttestation.sign(attestation, key)
                .signature();
        assertThat(verifier.verify(attestation, signature, key))
                .isTrue();
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"a", "secret", "long-secret-123456"})
    void parameterizedKeys(String keyValue) {
        byte[] key = keyValue.getBytes(StandardCharsets.UTF_8);
        Attestation attestation = attestation(0);
        Signed signed = SignedAttestation.sign(attestation, key);
        assertThat(verifier.verify(signed, key)).isTrue();
    }

    @ParameterizedTest(name = "index {0}")
    @ValueSource(ints = {0, 1, 100})
    void parameterizedAttestations(int index) {
        byte[] key = key("secret");
        Attestation attestation = attestation(index);
        Signed signed = SignedAttestation.sign(attestation, key);
        assertThat(verifier.verify(signed, key)).isTrue();
    }

    @Test
    void concurrentSignVerifyStable() throws Exception {
        byte[] key = key("secret");
        Attestation attestation = attestation(5);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    Signed signed = SignedAttestation.sign(
                            attestation, key);
                    assertThat(verifier.verify(signed, key))
                            .isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static Attestation attestation(int index) {
        String prev = index == 0 ? "" : "prev" + (index - 1);
        return new Attestation(index, "GDPR", "v1", 0, prev,
                AttestationChain.hash(index, "GDPR", "v1", 0,
                        prev), 1000 + index);
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
