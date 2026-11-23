package io.tieringkv.config;

import io.tieringkv.config.CredentialProbe.ProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实凭据 v6（ADR-0253）：延迟探测握手 + 降级登记。 */
class CredentialProbeV6Test {

    @Test
    void latencyOk() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5, 100);
        assertThat(result.ok()).isTrue();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void latencyExceeds() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 500, 100);
        assertThat(result.ok()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).hasSize(1);
        assertThat(probe.failures().get(0).detail())
                .contains("latency");
    }

    @Test
    void unreachableLatency() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> false,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> -1, 100);
        assertThat(result.ok()).isFalse();
    }

    @Test
    void nullLatencyRejected() {
        CredentialProbe probe = probe();
        assertThatThrownBy(() -> probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true, null, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realLatencyProbe() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 42, 1000);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void degradedOnLatency() {
        CredentialProbe probe = probe();
        probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 9999, 100);
        assertThat(probe.degraded()).isTrue();
    }

    @ParameterizedTest(name = "reach={0} auth={1} perm={2} quota={3}")
    @CsvSource({
            "true,true,true,true,5,100,true,false",
            "true,true,true,true,150,100,false,true",
            "true,true,true,false,5,100,false,true",
            "true,true,false,true,5,100,false,true",
            "true,false,true,true,5,100,false,true",
            "false,true,true,true,5,100,false,true",
            "true,true,false,false,5,100,false,true",
            "false,false,true,true,5,100,false,true",
            "true,false,false,true,5,100,false,true",
            "false,false,false,false,5,100,false,true",
            "true,true,true,true,0,0,true,false",
            "true,true,true,true,1,0,false,true",
            "true,true,true,true,100,100,true,false",
            "true,true,true,true,101,100,false,true",
            "true,true,true,true,10,1000,true,false",
            "true,true,true,true,-1,100,false,true",
            "true,true,true,true,999,1000,true,false",
            "true,true,true,true,1000,999,false,true",
            "true,true,true,true,50,50,true,false",
            "true,true,true,true,51,50,false,true",
            "true,true,true,true,25,100,true,false",
            "true,true,true,true,75,100,true,false",
            "true,true,true,true,125,100,false,true",
            "true,true,true,true,200,500,true,false",
            "true,true,true,true,501,500,false,true"
    })
    void parameterizedLatencyMatrix(boolean reachable,
                                    boolean authValid,
                                    boolean allowed,
                                    boolean withinQuota,
                                    long latency,
                                    long maxLatency,
                                    boolean expectedOk,
                                    boolean expectedDegraded) {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid,
                (endpoint, credential) -> allowed,
                (endpoint, credential) -> withinQuota,
                (endpoint, timeout) -> latency, maxLatency);
        assertThat(result.ok()).isEqualTo(expectedOk);
        assertThat(result.degraded()).isEqualTo(expectedDegraded);
    }

    @ParameterizedTest(name = "maxLatency {0}")
    @ValueSource(longs = {10, 50, 100, 500})
    void parameterizedMaxLatency(long maxLatency) {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> maxLatency / 2,
                maxLatency);
        assertThat(result.ok()).isTrue();
    }

    private static CredentialProbe probe() {
        return new CredentialProbe(CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
    }
}
