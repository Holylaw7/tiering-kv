package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;
import io.tieringkv.compliance.ContinuousAuditPipeline.AuditRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 合规持续证明（ADR-0167）：哈希链 + 验证 + 篡改检测。 */
class AttestationChainTest {

    @Test
    void appendCreatesLinkedAttestation() {
        AttestationChain chain = new AttestationChain();
        Attestation first = chain.append("GDPR", "v1", 0, 1000);
        Attestation second = chain.append("GDPR", "v2", 1, 2000);
        assertThat(first.index()).isZero();
        assertThat(first.prevHash()).isEmpty();
        assertThat(second.index()).isEqualTo(1);
        assertThat(second.prevHash()).isEqualTo(first.hash());
        assertThat(chain.size()).isEqualTo(2);
    }

    @Test
    void verifyTrueForValidChain() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        chain.append("GDPR", "v1", 1, 2000);
        chain.append("SOC2", "s1", 0, 3000);
        assertThat(chain.verify()).isTrue();
    }

    @Test
    void emptyChainVerifiesTrue() {
        assertThat(new AttestationChain().verify()).isTrue();
        assertThat(new AttestationChain().size()).isZero();
    }

    @Test
    void tamperedViolationsDetected() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        Attestation original = chain.append("GDPR", "v1", 1, 2000);
        Attestation tampered = new Attestation(original.index(),
                original.regulation(), original.versionId(), 99,
                original.prevHash(), original.hash(),
                original.timestampMillis());
        AttestationChain tamperedChain = new AttestationChain(
                List.of(chain.attestations().get(0), tampered));
        assertThat(tamperedChain.verify()).isFalse();
    }

    @Test
    void brokenPrevLinkDetected() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        Attestation broken = new Attestation(1, "GDPR", "v1", 0,
                "deadbeef", AttestationChain.hash(1, "GDPR", "v1",
                        0, "deadbeef"), 2000);
        AttestationChain brokenChain = new AttestationChain(
                List.of(chain.attestations().get(0), broken));
        assertThat(brokenChain.verify()).isFalse();
    }

    @Test
    void appendFromAuditRun() {
        AttestationChain chain = new AttestationChain();
        Attestation attestation = chain.append(new AuditRun(
                "GDPR", "v1", 1000, 2, "[]"));
        assertThat(attestation.regulation()).isEqualTo("GDPR");
        assertThat(attestation.violations()).isEqualTo(2);
        assertThat(chain.verify()).isTrue();
    }

    @Test
    void nullRunRejected() {
        assertThatThrownBy(() -> new AttestationChain()
                .append((AuditRun) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRegulationRejected() {
        assertThatThrownBy(() -> new AttestationChain()
                .append("", "v1", 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankVersionRejected() {
        assertThatThrownBy(() -> new AttestationChain()
                .append("GDPR", " ", 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeViolationsRejected() {
        assertThatThrownBy(() -> new AttestationChain()
                .append("GDPR", "v1", -1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashIsDeterministic() {
        String first = AttestationChain.hash(1, "GDPR", "v1", 0, "");
        String second = AttestationChain.hash(1, "GDPR", "v1", 0, "");
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void hashDiffersOnInputChange() {
        String base = AttestationChain.hash(1, "GDPR", "v1", 0, "");
        assertThat(AttestationChain.hash(2, "GDPR", "v1", 0, ""))
                .isNotEqualTo(base);
        assertThat(AttestationChain.hash(1, "SOC2", "v1", 0, ""))
                .isNotEqualTo(base);
        assertThat(AttestationChain.hash(1, "GDPR", "v1", 1, ""))
                .isNotEqualTo(base);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedChainLengths(int length) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < length; i++) {
            chain.append("GDPR", "v" + (i % 3), i % 5, i * 100L);
        }
        assertThat(chain.size()).isEqualTo(length);
        assertThat(chain.verify()).isTrue();
    }

    @ParameterizedTest(name = "violations {0}")
    @ValueSource(ints = {0, 1, 100})
    void parameterizedViolations(int violations) {
        AttestationChain chain = new AttestationChain();
        Attestation attestation = chain.append("GDPR", "v1",
                violations, 1000);
        assertThat(attestation.violations()).isEqualTo(violations);
        assertThat(chain.verify()).isTrue();
    }

    @Test
    void concurrentAppendsSerialized() throws Exception {
        AttestationChain chain = new AttestationChain();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    chain.append("GDPR", "v1", 0, i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(chain.size()).isEqualTo(200);
        assertThat(chain.verify()).isTrue();
    }

    @Test
    void attestationsAreCopied() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        List<Attestation> view = chain.attestations();
        chain.append("GDPR", "v1", 0, 2000);
        assertThat(view).hasSize(1);
        assertThat(chain.size()).isEqualTo(2);
    }

    @Test
    void negativeIndexAttestationRejected() {
        assertThatThrownBy(() -> new Attestation(-1, "GDPR",
                "v1", 0, "", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidAttestationFieldsRejected() {
        assertThatThrownBy(() -> new Attestation(0, "", "v1",
                0, "", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attestation(0, "GDPR", "",
                0, "", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attestation(0, "GDPR", "v1",
                -1, "", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
