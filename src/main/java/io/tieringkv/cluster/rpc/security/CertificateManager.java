package io.tieringkv.cluster.rpc.security;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.file.Files;
import java.util.function.Supplier;

/**
 * 证书生命周期管理（ADR-0055）：load / validate / expire detection /
 * reload / atomic rotation；SslContext 引用原子切换，已有连接不中断。
 */
public final class CertificateManager {

    private volatile SslContext serverContext;
    private volatile SslContext clientContext;
    private volatile CertificateInfo info;

    public static CertificateManager load(Path cert, Path key, Path ca) {
        CertificateManager manager = new CertificateManager();
        manager.reload(cert, key, ca);
        return manager;
    }

    public synchronized void reload(Path cert, Path key, Path ca) {
        try {
            X509Certificate certificate = parseCertificate(cert);
            CertificateInfo newInfo = new CertificateInfo(
                    cert, key, ca,
                    certificate.getNotBefore().getTime(),
                    certificate.getNotAfter().getTime());
            SslContextBuilder serverBuilder = SslContextBuilder.forServer(
                    cert.toFile(), key.toFile());
            SslContextBuilder clientBuilder = SslContextBuilder.forClient();
            if (ca != null) {
                serverBuilder.trustManager(ca.toFile());
                clientBuilder.trustManager(ca.toFile());
            }
            this.serverContext = serverBuilder.build();
            this.clientContext = clientBuilder.build();
            this.info = newInfo;
        } catch (Exception e) {
            throw new IllegalStateException("certificate load failed", e);
        }
    }

    public synchronized void rotate(Path cert, Path key, Path ca) {
        reload(cert, key, ca);
    }

    public Supplier<SslContext> serverContextSupplier() {
        return () -> serverContext;
    }

    public Supplier<SslContext> clientContextSupplier() {
        return () -> clientContext;
    }

    public SslContext serverContext() {
        return serverContext;
    }

    public SslContext clientContext() {
        return clientContext;
    }

    public CertificateInfo info() {
        return info;
    }

    /** 距过期毫秒数；已过期返回 0。 */
    public long expiresInMillis(long now) {
        return Math.max(0, info.notAfterMillis() - now);
    }

    public boolean expired(long now) {
        return info.expiredAt(now);
    }

    private static X509Certificate parseCertificate(Path cert) throws Exception {
        try (var in = Files.newInputStream(cert)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
        }
    }
}
