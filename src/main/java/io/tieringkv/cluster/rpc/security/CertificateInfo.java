package io.tieringkv.cluster.rpc.security;

import java.nio.file.Path;

/** 证书信息（ADR-0055）：路径与有效期。 */
public record CertificateInfo(
        Path certFile,
        Path keyFile,
        Path caFile,
        long notBeforeMillis,
        long notAfterMillis) {

    public boolean expiredAt(long now) {
        return now >= notAfterMillis;
    }

    public boolean notYetValidAt(long now) {
        return now < notBeforeMillis;
    }
}
