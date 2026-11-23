package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 第三方证明验证（ADR-0174）：独立校验 + 篡改检测。 */
class AttestationVerifierTest {

    private final AttestationVerifier verifier =
            new AttestationVerifier();

    @Test
    void validChainVerifies() {
        AttestationChain chain = chain(5);
        assertThat(verifier.verify(chain.attestations())).isTrue();
    }

    @Test
    void emptyChainVerifies() {
        assertThat(verifier.verify(List.of())).isTrue();
    }

    @Test
    void tamperedViolationsDetected() {
        AttestationChain chain = chain(2);
        List<Attestation> attestations =
                new ArrayList<>(chain.attestations());
        Attestation original = attestations.get(1);
        attestations.set(1, new Attestation(original.index(),
                original.regulation(), original.versionId(), 99,
                original.prevHash(), original.hash(),
                original.timestampMillis()));
        assertThat(verifier.verify(attestations)).isFalse();
    }

    @Test
    void brokenPrevLinkDetected() {
        AttestationChain chain = chain(2);
        List<Attestation> attestations =
                new ArrayList<>(chain.attestations());
        Attestation original = attestations.get(1);
        attestations.set(1, new Attestation(original.index(),
                original.regulation(), original.versionId(),
                original.violations(), "deadbeef", original.hash(),
                original.timestampMillis()));
        assertThat(verifier.verify(attestations)).isFalse();
    }

    @Test
    void singleAttestationWithPrevVerifies() {
        AttestationChain chain = chain(2);
        Attestation first = chain.attestations().get(0);
        Attestation second = chain.attestations().get(1);
        assertThat(verifier.verify(first, "")).isTrue();
        assertThat(verifier.verify(second, first.hash())).isTrue();
    }

    @Test
    void singleAttestationWrongPrevRejected() {
        AttestationChain chain = chain(1);
        Attestation first = chain.attestations().get(0);
        assertThat(verifier.verify(first, "wrong")).isFalse();
    }

    @Test
    void nullListRejected() {
        assertThatThrownBy(() -> verifier.verify((List<Attestation>)
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAttestationRejected() {
        assertThatThrownBy(() -> verifier.verify(null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedChainLengths(int length) {
        assertThat(verifier.verify(chain(length).attestations()))
                .isTrue();
    }

    @Test
    void concurrentVerificationStable() throws Exception {
        List<Attestation> attestations = chain(50).attestations();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(verifier.verify(attestations))
                            .isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static AttestationChain chain(int length) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < length; i++) {
            chain.append("GDPR", "v" + (i % 3), i % 4, i);
        }
        return chain;
    }
}
