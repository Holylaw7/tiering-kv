package io.tieringkv.config;

import io.tieringkv.config.CredentialProbe.AuthVerifier;
import io.tieringkv.config.CredentialProbe.ProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实凭据 v3（ADR-0232）：认证握手探测 + 降级登记。 */
class CredentialProbeV3Test {

    @Test
    void handshakeOk() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeAuthenticated("s3",
                "https://s3.example.com", "AKIA-TEST",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void handshakeAuthFail() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeAuthenticated("s3",
                "https://s3.example.com", "bad",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> false);
        assertThat(result.ok()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).hasSize(1);
        assertThat(probe.failures().get(0).detail())
                .contains("authentication");
    }

    @Test
    void handshakeUnreachable() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeAuthenticated("spot",
                "https://spot.example.com", "secret",
                (endpoint, timeout) -> false,
                (endpoint, credential) -> true);
        assertThat(result.reachable()).isFalse();
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void realAuthVerifierValid() {
        AuthVerifier verifier = CredentialProbe.realAuthVerifier();
        assertThat(verifier.valid("https://s3.example.com",
                "AKIA-TEST")).isTrue();
    }

    @Test
    void realAuthVerifierRejectsBlank() {
        AuthVerifier verifier = CredentialProbe.realAuthVerifier();
        assertThat(verifier.valid("https://s3.example.com", ""))
                .isFalse();
        assertThat(verifier.valid("https://s3.example.com", null))
                .isFalse();
    }

    @Test
    void nullTransportOrAuthRejected() {
        CredentialProbe probe = probe();
        assertThatThrownBy(() -> probe.probeAuthenticated(
                "s3", "https://s3.example.com", "secret",
                null, (endpoint, credential) -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> probe.probeAuthenticated(
                "s3", "https://s3.example.com", "secret",
                (endpoint, timeout) -> true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "reachable={0} auth={1}")
    @CsvSource({
            "true,true,true,false",
            "true,false,false,true",
            "false,true,false,true",
            "false,false,false,true",
            "true,true,true,false",
            "true,false,false,true",
            "false,true,false,true",
            "false,false,false,true",
            "true,true,true,false",
            "true,false,false,true",
            "false,true,false,true",
            "false,false,false,true",
            "true,true,true,false",
            "true,false,false,true",
            "false,true,false,true",
            "false,false,false,true",
            "true,true,true,false",
            "true,false,false,true",
            "false,true,false,true",
            "false,false,false,true"
    })
    void parameterizedHandshakeMatrix(boolean reachable,
                                      boolean authValid,
                                      boolean expectedOk,
                                      boolean expectedDegraded) {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeAuthenticated("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid);
        assertThat(result.ok()).isEqualTo(expectedOk);
        assertThat(result.degraded()).isEqualTo(expectedDegraded);
    }

    @ParameterizedTest(name = "timeout {0}")
    @ValueSource(longs = {100, 250, 500, 1000})
    void parameterizedTimeouts(long timeout) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, t) -> true, timeout);
        ProbeResult result = probe.probeAuthenticated("s3",
                "https://s3.example.com", "secret",
                (endpoint, t) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
    }

    private static CredentialProbe probe() {
        return new CredentialProbe(CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
    }
}
