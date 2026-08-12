package io.tieringkv.config;

import io.tieringkv.config.CredentialProbe.Mode;
import io.tieringkv.config.CredentialProbe.ProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实凭据 v2（ADR-0225）：真实 HTTP 探针 + 降级登记矩阵。 */
class CredentialProbeV2Test {

    @Test
    void realHttpProberFactoryReturnsProber() {
        CredentialProbe.EndpointProber prober =
                CredentialProbe.realHttpProber(500);
        assertThat(prober).isNotNull();
    }

    @Test
    void realHttpProberRejectsInvalidUriWithoutNetwork() {
        CredentialProbe.EndpointProber prober =
                CredentialProbe.realHttpProber(500);
        assertThat(prober.reachable("not a uri", 500))
                .isFalse();
    }

    @Test
    void autoModeUsesRealHttpWhenConfigured() {
        CredentialProbe probe = new CredentialProbe(
                Mode.AUTO,
                CredentialProbe.realHttpProber(500), 500);
        ProbeResult result = probe.probe("s3",
                "https://s3.example.com", "secret");
        assertThat(result.mode()).isEqualTo(Mode.REAL);
    }

    @Test
    void failuresRegisteredImmutably() {
        CredentialProbe probe = new CredentialProbe(
                Mode.REAL, (endpoint, timeout) -> false, 500);
        probe.probe("s3", "https://x.example.com", "secret");
        assertThat(probe.failures()).hasSize(1);
        assertThatThrownBy(() -> probe.failures().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void degradedReflectsFailures() {
        CredentialProbe probe = new CredentialProbe(
                Mode.REAL, (endpoint, timeout) -> false, 500);
        probe.probe("spot", "https://x.example.com", "secret");
        assertThat(probe.degraded()).isTrue();
        CredentialProbe ok = new CredentialProbe(
                Mode.SIMULATED, (endpoint, timeout) -> true,
                500);
        ok.probe("s3", "https://x.example.com", "secret");
        assertThat(ok.degraded()).isFalse();
    }

    @ParameterizedTest(name = "mode={0} endpoint={1} cred={2} probed={3}")
    @CsvSource({
            "SIMULATED,true,true,true,false",
            "SIMULATED,true,false,true,true",
            "SIMULATED,false,true,true,false",
            "SIMULATED,false,false,true,true",
            "REAL,true,true,true,false",
            "REAL,true,true,false,true",
            "REAL,true,false,true,true",
            "REAL,true,false,false,true",
            "REAL,false,true,true,true",
            "REAL,false,false,true,true",
            "AUTO,true,true,true,false",
            "AUTO,true,true,false,true",
            "AUTO,true,false,true,true",
            "AUTO,false,true,true,false",
            "AUTO,false,false,true,true",
            "SIMULATED,true,true,false,false",
            "SIMULATED,false,true,false,false",
            "REAL,true,true,true,false",
            "REAL,false,true,false,true",
            "AUTO,true,true,true,false"
    })
    void parameterizedProbeV2Matrix(Mode mode,
                                    boolean endpointPresent,
                                    boolean credentialPresent,
                                    boolean probed,
                                    boolean expectedDegraded) {
        CredentialProbe probe = new CredentialProbe(mode,
                (endpoint, timeout) -> probed, 500);
        ProbeResult result = probe.probe("s3",
                endpointPresent ? "https://x.example.com" : "",
                credentialPresent ? "secret" : "");
        assertThat(result.degraded())
                .isEqualTo(expectedDegraded);
    }
}
