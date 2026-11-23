package io.tieringkv.config;

import io.tieringkv.config.CredentialProbe.ProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实凭据 v4（ADR-0239）：权限校验握手 + 降级登记。 */
class CredentialProbeV4Test {

    @Test
    void permissionOk() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void permissionDenied() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> false);
        assertThat(result.ok()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(probe.failures()).hasSize(1);
        assertThat(probe.failures().get(0).detail())
                .contains("permission");
    }

    @Test
    void nullPermissionRejected() {
        CredentialProbe probe = probe();
        assertThatThrownBy(() -> probe.probeWithPermission(
                "s3", "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realPermissionCheckAllowsNonBlank() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> credential != null
                        && !credential.isBlank());
        assertThat(result.ok()).isTrue();
    }

    @Test
    void handshakeFailSkipsPermission() {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithPermission("spot",
                "https://spot.example.com", "secret",
                (endpoint, timeout) -> false,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isFalse();
        assertThat(probe.failures()).hasSize(1);
    }

    @Test
    void degradedRegisteredOnPermissionDenial() {
        CredentialProbe probe = probe();
        probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> false);
        assertThat(probe.degraded()).isTrue();
    }

    @ParameterizedTest(name = "reach={0} auth={1} perm={2}")
    @CsvSource({
            "true,true,true,true,false",
            "true,true,false,false,true",
            "true,false,true,false,true",
            "false,true,true,false,true",
            "true,false,false,false,true",
            "false,false,true,false,true",
            "false,true,false,false,true",
            "false,false,false,false,true",
            "true,true,true,true,false",
            "true,true,false,false,true",
            "true,false,true,false,true",
            "false,true,true,false,true",
            "true,false,false,false,true",
            "false,false,true,false,true",
            "false,true,false,false,true",
            "false,false,false,false,true",
            "true,true,true,true,false",
            "true,true,false,false,true",
            "true,false,true,false,true",
            "false,true,true,false,true"
    })
    void parameterizedPermissionMatrix(boolean reachable,
                                       boolean authValid,
                                       boolean allowed,
                                       boolean expectedOk,
                                       boolean expectedDegraded) {
        CredentialProbe probe = probe();
        ProbeResult result = probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid,
                (endpoint, credential) -> allowed);
        assertThat(result.ok()).isEqualTo(expectedOk);
        assertThat(result.degraded()).isEqualTo(expectedDegraded);
    }

    @ParameterizedTest(name = "timeout {0}")
    @ValueSource(longs = {100, 250, 500, 1000})
    void parameterizedTimeouts(long timeout) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, t) -> true, timeout);
        ProbeResult result = probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, t) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
    }

    private static CredentialProbe probe() {
        return new CredentialProbe(CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
    }
}
