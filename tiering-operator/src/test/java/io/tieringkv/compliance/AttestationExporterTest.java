package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 证明导出（ADR-0174）：JSON 交换 + 第三方解析。 */
class AttestationExporterTest {

    private final AttestationExporter exporter =
            new AttestationExporter();

    @Test
    void exportRoundTrip() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        chain.append("GDPR", "v2", 1, 2000);
        String json = exporter.toJson(chain);
        List<Attestation> parsed = exporter.fromJson(json);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0)).isEqualTo(
                chain.attestations().get(0));
        assertThat(parsed.get(1)).isEqualTo(
                chain.attestations().get(1));
    }

    @Test
    void emptyChainJson() {
        assertThat(exporter.toJson(List.of())).isEqualTo("[]");
        assertThat(exporter.fromJson("[]")).isEmpty();
    }

    @Test
    void parsedChainVerifiesIndependently() {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < 10; i++) {
            chain.append("GDPR", "v1", i % 3, i);
        }
        List<Attestation> parsed = exporter.fromJson(
                exporter.toJson(chain));
        assertThat(new AttestationVerifier().verify(parsed))
                .isTrue();
    }

    @Test
    void jsonContainsFields() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 2, 1000);
        String json = exporter.toJson(chain);
        assertThat(json).startsWith("[{").endsWith("}]")
                .contains("\"regulation\":\"GDPR\"")
                .contains("\"violations\":\"2\"")
                .contains("\"timestampMillis\":\"1000\"");
    }

    @Test
    void nullListRejected() {
        assertThatThrownBy(() -> exporter.toJson(
                (List<Attestation>) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullChainRejected() {
        assertThatThrownBy(() -> exporter.toJson(
                (AttestationChain) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidJsonRejected() {
        assertThatThrownBy(() -> exporter.fromJson("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exporter.fromJson(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incompleteObjectRejected() {
        assertThatThrownBy(() -> exporter.fromJson(
                "[{\"index\":\"0\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonEscapesValues() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v\"1", 0, 1000);
        String json = exporter.toJson(chain);
        assertThat(json).contains("v\\\"1");
        assertThat(exporter.fromJson(json).get(0).versionId())
                .isEqualTo("v\"1");
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 5, 50})
    void parameterizedExportLengths(int length) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < length; i++) {
            chain.append("R" + (i % 3), "v1", i % 4, i);
        }
        List<Attestation> parsed = exporter.fromJson(
                exporter.toJson(chain));
        assertThat(parsed).hasSize(length);
    }

    @Test
    void concurrentExportStable() throws Exception {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < 20; i++) {
            chain.append("GDPR", "v1", 0, i);
        }
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(exporter.fromJson(
                            exporter.toJson(chain))).hasSize(20);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }
}
