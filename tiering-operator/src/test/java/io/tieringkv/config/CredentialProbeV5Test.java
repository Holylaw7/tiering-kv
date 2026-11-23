package io.tieringkv.config;

import io.tieringkv.config.CredentialProbe.ProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实凭据 v5（ADR-0246）：配额校验握手 + 降级登记。 */
class CredentialProbeV5Test {

    @Test
    void quotaOk() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void quotaExceeded() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> false);
        assertThat(result.ok()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).hasSize(1);
        assertThat(probe.failures().get(0).detail())
                .contains("quota");
    }

    @Test
    void nullQuotaRejected() {
        CredentialProbe probe = probe();
        assertThatThrownBy(() -> probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quotaSkippedWhenBaseFails() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> false,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isFalse();
        assertThat(probe.failures()).hasSize(1);
    }

    @Test
    void realQuotaCheck() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> credential != null
                        && credential.length() > 3);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void degradedOnQuota() {
        CredentialProbe probe = probe();
        probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> false);
        assertThat(probe.degraded()).isTrue();
    }

    @ParameterizedTest(name = "reach={0} auth={1} perm={2} quota={3}")
    @CsvSource({
            "true,true,true,true,true,false",
            "true,true,true,false,false,true",
            "true,true,false,true,false,true",
            "true,false,true,true,false,true",
            "false,true,true,true,false,true",
            "true,true,false,false,false,true",
            "true,false,false,true,false,true",
            "false,false,true,true,false,true",
            "false,true,false,true,false,true",
            "true,false,true,false,false,true",
            "false,false,false,true,false,true",
            "false,false,false,false,false,true",
            "true,true,true,true,true,false",
            "true,true,true,false,false,true",
            "true,true,false,true,false,true",
            "true,false,true,true,false,true",
            "false,true,true,true,false,true",
            "true,true,false,false,false,true",
            "true,false,false,true,false,true",
            "false,false,true,true,false,true",
            "false,true,false,true,false,true",
            "true,false,true,false,false,true",
            "true,true,true,true,true,false",
            "true,true,true,false,false,true",
            "true,true,false,true,false,true"
    })
    void parameterizedQuotaMatrix(boolean reachable,
                                  boolean authValid,
                                  boolean allowed,
                                  boolean withinQuota,
                                  boolean expectedOk,
                                  boolean expectedDegraded) {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid,
                (endpoint, credential) -> allowed,
                (endpoint, credential) -> withinQuota);
        assertThat(result.ok()).isEqualTo(expectedOk);
        assertThat(result.degraded()).isEqualTo(expectedDegraded);
    }

    @ParameterizedTest(name = "timeout {0}")
    @ValueSource(longs = {100, 250, 500, 1000})
    void parameterizedTimeouts(long timeout) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, t) -> true, timeout);
        ProbeResult result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, t) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
    }

    private static CredentialProbe probe() {
        return new CredentialProbe(CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
    }
}
