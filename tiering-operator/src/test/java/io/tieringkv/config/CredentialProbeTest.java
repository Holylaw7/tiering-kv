package io.tieringkv.config;

import io.tieringkv.config.CredentialProbe.Mode;
import io.tieringkv.config.CredentialProbe.ProbeResult;
import io.tieringkv.datamesh.S3ObjectStorage;
import io.tieringkv.observability.cost.SpotMarketDataSource;
import io.tieringkv.observability.cost.SpotMarketFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实凭据探测（ADR-0218）：S3/Spot 连通性 + 降级登记。 */
class CredentialProbeTest {

    @Test
    void simulatedProbeOk() {
        CredentialProbe probe = probe(Mode.SIMULATED,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probe("s3",
                "https://s3.example.com", "AKIA-TEST");
        assertThat(result.ok()).isTrue();
        assertThat(result.degraded()).isFalse();
        assertThat(result.mode()).isEqualTo(Mode.SIMULATED);
    }

    @Test
    void simulatedProbeMissingCredentialDegraded() {
        CredentialProbe probe = probe(Mode.SIMULATED,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probe("s3",
                "https://s3.example.com", " ");
        assertThat(result.ok()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).hasSize(1);
    }

    @Test
    void realProbeReachableAndValid() {
        CredentialProbe probe = probe(Mode.REAL,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probe("spot",
                "https://spot.example.com", "secret");
        assertThat(result.ok()).isTrue();
        assertThat(probe.degraded()).isFalse();
    }

    @Test
    void realProbeUnreachableDegradedAndRegistered() {
        CredentialProbe probe = probe(Mode.REAL,
                (endpoint, timeout) -> false);
        ProbeResult result = probe.probe("s3",
                "https://unreachable.example.com", "secret");
        assertThat(result.reachable()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).hasSize(1);
        assertThat(probe.failures().get(0).target())
                .isEqualTo("s3");
    }

    @Test
    void realProbeReachableButMissingCredential() {
        CredentialProbe probe = probe(Mode.REAL,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probe("s3",
                "https://s3.example.com", null);
        assertThat(result.credentialValid()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures().get(0).detail())
                .contains("credential");
    }

    @Test
    void autoModeWithEndpointAndCredentialUsesReal() {
        CredentialProbe probe = probe(Mode.AUTO,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probe("s3",
                "https://s3.example.com", "AKIA-TEST");
        assertThat(result.mode()).isEqualTo(Mode.REAL);
    }

    @Test
    void autoModeWithoutCredentialFallsBackSimulated() {
        CredentialProbe probe = probe(Mode.AUTO,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probe("s3",
                "https://s3.example.com", null);
        assertThat(result.mode()).isEqualTo(Mode.SIMULATED);
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void nullTargetRejected() {
        assertThatThrownBy(() -> probe(Mode.SIMULATED,
                (endpoint, timeout) -> true).probe(null, "e", "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConstructorRejected() {
        assertThatThrownBy(() -> new CredentialProbe(null,
                (endpoint, timeout) -> true, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CredentialProbe(
                Mode.SIMULATED, (endpoint, timeout) -> true, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void probeS3Integration() {
        S3ObjectStorage storage = new S3ObjectStorage("bucket",
                "https://s3.example.com");
        CredentialProbe probe = probe(Mode.SIMULATED,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probeS3(storage, "AKIA-TEST");
        assertThat(result.target()).isEqualTo("s3");
        assertThat(result.ok()).isTrue();
    }

    @Test
    void probeSpotIntegration() {
        SpotMarketDataSource source =
                new SpotMarketDataSource("https://spot.example.com",
                        new SpotMarketFeed());
        CredentialProbe probe = probe(Mode.SIMULATED,
                (endpoint, timeout) -> true);
        ProbeResult result = probe.probeSpot(source, "secret");
        assertThat(result.target()).isEqualTo("spot");
        assertThat(result.ok()).isTrue();
    }

    @Test
    void degradedClearsAfterFreshProbe() {
        CredentialProbe probe = probe(Mode.REAL,
                (endpoint, timeout) -> false);
        probe.probe("s3", "https://x.example.com", "c");
        assertThat(probe.degraded()).isTrue();
        CredentialProbe fresh = probe(Mode.REAL,
                (endpoint, timeout) -> true);
        assertThat(fresh.probe("s3",
                "https://x.example.com", "c").ok()).isTrue();
        assertThat(fresh.degraded()).isFalse();
    }

    @Test
    void failuresExposedImmutably() {
        CredentialProbe probe = probe(Mode.REAL,
                (endpoint, timeout) -> false);
        probe.probe("s3", "https://x.example.com", "c");
        assertThatThrownBy(() -> probe.failures().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "mode={0} endpoint={1} credential={2} ok={3}")
    @CsvSource({
            "SIMULATED,https://s3.example.com,AKIA-TEST,true",
            "SIMULATED,https://s3.example.com,,false",
            "SIMULATED,,AKIA-TEST,true",
            "SIMULATED,,,false",
            "REAL,https://s3.example.com,AKIA-TEST,true",
            "REAL,https://s3.example.com,,false",
            "REAL,,AKIA-TEST,false",
            "AUTO,https://s3.example.com,AKIA-TEST,true",
            "AUTO,https://s3.example.com,,false",
            "AUTO,,AKIA-TEST,true",
            "AUTO,,,false",
            "SIMULATED,https://spot.example.com,secret,true",
            "SIMULATED,https://spot.example.com,,false",
            "REAL,https://spot.example.com,secret,true",
            "REAL,https://spot.example.com,,false"
    })
    void parameterizedProbeMatrix(Mode mode, String endpoint,
                                  String credential,
                                  boolean expectedOk) {
        CredentialProbe probe = probe(mode,
                (ignoredEndpoint, timeout) -> true);
        ProbeResult result = probe.probe("s3", endpoint,
                credential);
        assertThat(result.ok()).isEqualTo(expectedOk);
        assertThat(result.degraded())
                .isEqualTo(!expectedOk);
    }

    @ParameterizedTest(name = "probed={0} credential={1} reachable={2}")
    @CsvSource({
            "true,true,true",
            "true,false,false",
            "false,true,false",
            "false,false,false",
            "true,true,true",
            "true,false,false",
            "false,true,false",
            "false,false,false",
            "true,true,true",
            "false,true,false"
    })
    void parameterizedProberResults(boolean probed,
                                    boolean credential,
                                    boolean expectedReachable) {
        CredentialProbe probe = probe(Mode.REAL,
                (endpoint, timeout) -> probed);
        ProbeResult result = probe.probe("s3",
                "https://x.example.com",
                credential ? "secret" : null);
        assertThat(result.reachable())
                .isEqualTo(expectedReachable);
        assertThat(result.credentialValid())
                .isEqualTo(credential);
    }

    private static CredentialProbe probe(
            CredentialProbe.Mode mode,
            CredentialProbe.EndpointProber prober) {
        return new CredentialProbe(mode, prober, 1000);
    }
}
