package io.tieringkv.config;

import io.tieringkv.config.CredentialProbe.JitterProbe;
import io.tieringkv.config.CredentialProbe.LatencyProbe;
import io.tieringkv.config.CredentialProbe.NetworkProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实凭据网络验证 v7（ADR-0260）：六项握手矩阵 + 降级登记。 */
class CredentialProbeV7Test {

    private CredentialProbe probe() {
        return new CredentialProbe(CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
    }

    @Test
    void fullHandshakeOk() {
        NetworkProbeResult result = probe().probeNetworkV7(
                "s3", "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5,
                (endpoint, timeout) -> 1, 100, 10);
        assertThat(result.ok()).isTrue();
        assertThat(result.latencyMillis()).isEqualTo(5);
        assertThat(result.jitterMillis()).isEqualTo(1);
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void failureRegisteredWhenUnreachable() {
        CredentialProbe probe = probe();
        NetworkProbeResult result = probe.probeNetworkV7(
                "spot", "https://spot.example.com", "secret",
                (endpoint, timeout) -> false,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> -1,
                (endpoint, timeout) -> -1, 100, 10);
        assertThat(result.ok()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).isNotEmpty();
    }

    @Test
    void missingEndpointDegrades() {
        NetworkProbeResult result = probe().probeNetworkV7(
                "s3", "", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> -1,
                (endpoint, timeout) -> -1, 100, 10);
        assertThat(result.degraded()).isTrue();
        assertThat(result.ok()).isFalse();
    }

    @Test
    void jitterExceededDegradesAndRegisters() {
        CredentialProbe probe = probe();
        NetworkProbeResult result = probe.probeNetworkV7(
                "s3", "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5,
                (endpoint, timeout) -> 50, 100, 10);
        assertThat(result.ok()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).hasSize(1);
    }

    @Test
    void validationRejectsNullJitter() {
        assertThatThrownBy(() -> probe().probeNetworkV7(
                "s3", "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5, null, 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "latency {0} max {1}")
    @CsvSource({
            "0, 100", "5, 100", "99, 100", "100, 100",
            "101, 100", "500, 100", "1, 1", "0, 0",
            "-1, 100", "1000, 500"
    })
    void latencyMatrix(long latency, long maxLatency) {
        NetworkProbeResult result = probe().probeNetworkV7(
                "s3", "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> latency,
                (endpoint, timeout) -> 1, maxLatency, 10);
        boolean expectedOk = latency >= 0
                && latency <= maxLatency;
        assertThat(result.ok()).isEqualTo(expectedOk);
        assertThat(result.degraded())
                .isEqualTo(!expectedOk);
    }

    @ParameterizedTest(name = "jitter {0} max {1}")
    @CsvSource({
            "0, 10", "5, 10", "10, 10", "11, 10", "50, 10",
            "100, 100", "101, 100", "-1, 10", "1, 0", "0, 0"
    })
    void jitterMatrix(long jitter, long maxJitter) {
        NetworkProbeResult result = probe().probeNetworkV7(
                "spot", "https://spot.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5,
                (endpoint, timeout) -> jitter, 100, maxJitter);
        boolean expectedOk = jitter >= 0
                && jitter <= maxJitter;
        assertThat(result.ok()).isEqualTo(expectedOk);
        assertThat(result.degraded())
                .isEqualTo(!expectedOk);
    }

    @ParameterizedTest(name = "target {0}")
    @MethodSource("targets")
    void targetRequiredValidation(String target) {
        assertThatThrownBy(() -> probe().probeNetworkV7(
                target, "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5,
                (endpoint, timeout) -> 1, 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> targets() {
        return Stream.of(null, "", "  ");
    }

    static Stream<Object> unused() {
        return Stream.of(new JitterProbe() {
            @Override
            public long jitterMillis(String endpoint,
                                     long timeoutMillis) {
                return 0;
            }
        }, new LatencyProbe() {
            @Override
            public long latencyMillis(String endpoint,
                                      long timeoutMillis) {
                return 0;
            }
        });
    }
}
