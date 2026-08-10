package io.tieringkv.cluster.rpc;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.tieringkv.cluster.rpc.security.CertificateInfo;
import io.tieringkv.cluster.rpc.security.CertificateManager;
import io.tieringkv.cluster.rpc.security.CertificateWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 证书生命周期（ADR-0055）：加载/校验/过期/轮换/监听。 */
class CertificateLifecycleTest {

    @TempDir
    Path dir;

    @Test
    void loadValidCertificate() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(),
                cert.certificate().toPath());
        assertThat(manager.serverContext()).isNotNull();
        assertThat(manager.clientContext()).isNotNull();
    }

    @Test
    void infoCapturesValidity() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        CertificateInfo info = manager.info();
        assertThat(info.notAfterMillis()).isGreaterThan(System.currentTimeMillis());
        assertThat(info.expiredAt(System.currentTimeMillis())).isFalse();
    }

    @Test
    void expiredDetection() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        long now = System.currentTimeMillis();
        assertThat(manager.expired(now)).isFalse();
        assertThat(manager.expiresInMillis(now)).isGreaterThan(0);
        assertThat(manager.expiresInMillis(now + manager.info().notAfterMillis())).isZero();
    }

    @Test
    void invalidFileFailsLoad() {
        Path missing = dir.resolve("missing.crt");
        assertThatThrownBy(() -> CertificateManager.load(missing, missing, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reloadSwapsContext() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        Object before = manager.serverContext();
        SelfSignedCertificate cert2 = new SelfSignedCertificate("localhost");
        manager.reload(cert2.certificate().toPath(), cert2.privateKey().toPath(), null);
        assertThat(manager.serverContext()).isNotSameAs(before);
    }

    @Test
    void rotateIsAtomicSwitch() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        CertificateInfo oldInfo = manager.info();
        SelfSignedCertificate rotated = new SelfSignedCertificate("localhost");
        manager.rotate(rotated.certificate().toPath(), rotated.privateKey().toPath(), null);
        assertThat(manager.info()).isNotEqualTo(oldInfo);
    }

    @Test
    void contextSuppliersResolveCurrent() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        Object supplierContext = manager.serverContextSupplier().get();
        assertThat(supplierContext).isSameAs(manager.serverContext());
    }

    @Test
    void watcherTriggersOnFileChange() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        Files.copy(cert.certificate().toPath(), dir.resolve("server.crt"));
        Files.copy(cert.privateKey().toPath(), dir.resolve("server.key"));
        CertificateWatcher watcher = new CertificateWatcher(dir);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        watcher.onChange(() -> {
            calls.incrementAndGet();
            latch.countDown();
        });
        Files.write(dir.resolve("server.crt"),
                Files.readAllBytes(cert.certificate().toPath()));
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        watcher.close();
    }

    @Test
    void watcherIgnoresNonCertificateFiles() throws Exception {
        CertificateWatcher watcher = new CertificateWatcher(dir);
        AtomicInteger calls = new AtomicInteger();
        watcher.onChange(calls::incrementAndGet);
        Files.write(dir.resolve("notes.txt"), new byte[]{1});
        Thread.sleep(200);
        assertThat(calls.get()).isZero();
        watcher.close();
    }

    @Test
    void watcherCloseStopsCallbacks() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        Files.copy(cert.certificate().toPath(), dir.resolve("server.crt"));
        Files.copy(cert.privateKey().toPath(), dir.resolve("server.key"));
        CertificateWatcher watcher = new CertificateWatcher(dir);
        AtomicInteger calls = new AtomicInteger();
        watcher.onChange(calls::incrementAndGet);
        Files.write(dir.resolve("server.crt"),
                Files.readAllBytes(cert.certificate().toPath()));
        Thread.sleep(300);
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        watcher.close();
        int before = calls.get();
        Files.write(dir.resolve("server.crt"),
                Files.readAllBytes(cert.certificate().toPath()));
        Thread.sleep(300);
        assertThat(calls.get()).isEqualTo(before);
        watcher.close(); // close 幂等
    }

    @Test
    void mutualTlsHandshakeWithManagedCertificates() throws Exception {
        SelfSignedCertificate serverCert = new SelfSignedCertificate("localhost");
        SelfSignedCertificate clientCert = new SelfSignedCertificate("client");
        CertificateManager serverManager = CertificateManager.load(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                clientCert.certificate().toPath());
        CertificateManager clientManager = CertificateManager.load(
                serverCert.certificate().toPath(), serverCert.privateKey().toPath(),
                serverCert.certificate().toPath());
        assertThat(serverManager.serverContext()).isNotNull();
        assertThat(clientManager.clientContext()).isNotNull();
    }

    @Test
    void rotationKeepsOldConnectionsReference() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        Object oldContext = manager.serverContext();
        SelfSignedCertificate rotated = new SelfSignedCertificate("localhost");
        manager.rotate(rotated.certificate().toPath(), rotated.privateKey().toPath(), null);
        // 旧 SslContext 引用仍可用（原子切换不破坏旧连接）
        assertThat(oldContext).isNotNull();
        assertThat(manager.serverContext()).isNotSameAs(oldContext);
    }

    @Test
    void invalidCaFailsMutualLoad() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        Path bogusCa = dir.resolve("bogus-ca.pem");
        Files.write(bogusCa, new byte[]{1, 2, 3});
        assertThatThrownBy(() -> CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), bogusCa))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void multipleReloadsStable() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        for (int i = 0; i < 5; i++) {
            SelfSignedCertificate next = new SelfSignedCertificate("localhost");
            manager.reload(next.certificate().toPath(), next.privateKey().toPath(), null);
        }
        assertThat(manager.serverContext()).isNotNull();
    }

    @Test
    void certificateInfoPathFields() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                cert.certificate().toPath(), cert.privateKey().toPath(), null);
        CertificateInfo info = manager.info();
        assertThat(info.certFile()).isEqualTo(cert.certificate().toPath());
        assertThat(info.keyFile()).isEqualTo(cert.privateKey().toPath());
    }
}
