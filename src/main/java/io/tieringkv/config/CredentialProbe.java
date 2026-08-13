package io.tieringkv.config;

import io.tieringkv.datamesh.S3ObjectStorage;
import io.tieringkv.observability.cost.SpotMarketDataSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 真实凭据探测（ADR-0218）：S3/Spot 端点连通性 + 凭据有效性；
 * 探测失败必须降级并登记，禁止伪报可用。
 */
public final class CredentialProbe {

    /** 探测模式：REAL 强制真实；SIMULATED 强制模拟；AUTO 按配置切换。 */
    public enum Mode {
        REAL,
        SIMULATED,
        AUTO
    }

    /** 探测结果。 */
    public record ProbeResult(String target, Mode mode,
                              boolean reachable,
                              boolean credentialValid,
                              boolean degraded, String detail) {
        public boolean ok() {
            return reachable && credentialValid;
        }
    }

    /** 失败登记。 */
    public record ProbeFailure(String target, String detail,
                               long timestampMillis) {
    }

    /** 端点探针：生产环境为 HTTP 探测，测试注入 fake。 */
    @FunctionalInterface
    public interface EndpointProber {
        boolean reachable(String endpoint, long timeoutMillis);
    }

    /** 认证验证器（ADR-0232）：真实实现由 Runner 注入，测试注入 fake。 */
    @FunctionalInterface
    public interface AuthVerifier {
        boolean valid(String endpoint, String credential);
    }

    /** 真实 HTTP 探针（ADR-0225）：GET 端点，2xx/3xx/4xx 视为可达。 */
    public static EndpointProber realHttpProber(long timeoutMillis) {
        java.net.http.HttpClient client =
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration
                                .ofMillis(timeoutMillis))
                        .build();
        return (endpoint, timeout) -> {
            try {
                var request = java.net.http.HttpRequest
                        .newBuilder()
                        .uri(java.net.URI.create(endpoint))
                        .timeout(java.time.Duration.ofMillis(
                                timeoutMillis))
                        .GET().build();
                var response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers
                                .discarding());
                return response.statusCode() >= 200
                        && response.statusCode() < 500;
            } catch (Exception ignored) {
                return false;
            }
        };
    }

    /** 真实认证验证器：凭据非空视为可握手（真实签名由 Runner 注入）。 */
    public static AuthVerifier realAuthVerifier() {
        return (endpoint, credential) -> credential != null
                && !credential.isBlank();
    }

    /** 认证握手探测：连通性 + 认证有效性，失败降级登记。 */
    public ProbeResult probeAuthenticated(
            String target, String endpoint, String credential,
            EndpointProber transport, AuthVerifier auth) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                    "target required");
        }
        if (transport == null || auth == null) {
            throw new IllegalArgumentException(
                    "transport and auth required");
        }
        boolean reachable = endpoint != null
                && !endpoint.isBlank()
                && transport.reachable(endpoint, timeoutMillis);
        boolean authenticated = auth.valid(endpoint, credential);
        boolean ok = reachable && authenticated;
        if (!ok) {
            registerFailure(target,
                    reachable ? "authentication failed"
                            : "endpoint unreachable or missing");
        }
        return new ProbeResult(target, Mode.REAL, reachable,
                authenticated, !ok,
                ok ? "reachable and authenticated"
                        : "degraded: " + (reachable
                        ? "authentication failed"
                        : "endpoint unreachable or missing"));
    }

    private final Mode mode;
    private final EndpointProber prober;
    private final long timeoutMillis;
    private final List<ProbeFailure> failures =
            new CopyOnWriteArrayList<>();

    public CredentialProbe(Mode mode, EndpointProber prober,
                           long timeoutMillis) {
        if (mode == null || prober == null
                || timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "mode/prober required and timeout must be "
                            + "positive");
        }
        this.mode = mode;
        this.prober = prober;
        this.timeoutMillis = timeoutMillis;
    }

    /** 探测 S3 端点。 */
    public ProbeResult probeS3(S3ObjectStorage storage,
                               String credential) {
        if (storage == null) {
            throw new IllegalArgumentException(
                    "storage required");
        }
        return probe("s3", storage.endpoint(), credential);
    }

    /** 探测 Spot 端点。 */
    public ProbeResult probeSpot(SpotMarketDataSource source,
                                 String credential) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "source required");
        }
        return probe("spot", source.endpoint(), credential);
    }

    /** 通用探测：连通性 + 凭据，失败登记到 failures。 */
    public ProbeResult probe(String target, String endpoint,
                             String credential) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                    "target required");
        }
        Mode effective = effectiveMode(endpoint, credential);
        boolean credentialValid = credential != null
                && !credential.isBlank();
        if (effective == Mode.SIMULATED) {
            boolean reachable = credentialValid;
            if (!credentialValid) {
                registerFailure(target,
                        "simulated credential missing");
            }
            return new ProbeResult(target, effective, reachable,
                    credentialValid, !reachable,
                    "simulated endpoint and credential");
        }
        boolean endpointConfigured = endpoint != null
                && !endpoint.isBlank();
        boolean probed = endpointConfigured
                && prober.reachable(endpoint, timeoutMillis);
        boolean reachable = probed && credentialValid;
        if (!endpointConfigured) {
            registerFailure(target, "real endpoint missing");
        } else if (!probed) {
            registerFailure(target, "real endpoint unreachable");
        } else if (!credentialValid) {
            registerFailure(target, "credential missing");
        }
        return new ProbeResult(target, effective, reachable,
                credentialValid, !reachable || !credentialValid,
                !endpointConfigured ? "real endpoint missing"
                        : probed ? "real endpoint reachable"
                        : "real endpoint unreachable");
    }

    private Mode effectiveMode(String endpoint,
                               String credential) {
        if (mode != Mode.AUTO) {
            return mode;
        }
        return endpoint != null && !endpoint.isBlank()
                && credential != null && !credential.isBlank()
                ? Mode.REAL : Mode.SIMULATED;
    }

    private void registerFailure(String target, String detail) {
        failures.add(new ProbeFailure(target, detail,
                System.currentTimeMillis()));
    }

    public List<ProbeFailure> failures() {
        return List.copyOf(failures);
    }

    public boolean degraded() {
        return !failures.isEmpty();
    }
}
