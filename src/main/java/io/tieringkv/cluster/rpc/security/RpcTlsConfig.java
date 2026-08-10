package io.tieringkv.cluster.rpc.security;

import java.nio.file.Path;

/** mTLS 配置（ADR-0051）：服务端/客户端证书 + CA。 */
public record RpcTlsConfig(
        TlsMode mode,
        Path serverCertFile,
        Path serverKeyFile,
        Path caFile,
        Path clientCertFile,
        Path clientKeyFile) {

    public static RpcTlsConfig oneWay(Path cert, Path key) {
        return new RpcTlsConfig(TlsMode.ONE_WAY, cert, key, null, null, null);
    }

    public static RpcTlsConfig mutual(Path serverCert, Path serverKey,
                                      Path ca, Path clientCert, Path clientKey) {
        return new RpcTlsConfig(TlsMode.MUTUAL, serverCert, serverKey,
                ca, clientCert, clientKey);
    }
}
