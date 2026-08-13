package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler
        .RegulatoryComplianceCertificate.Certificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 监管级合规证书（ADR-0245）：时间戳证书 + 轮换 + 外部验证。 */
class RegulatoryComplianceCertificateTest {

    @Test
    void issueCreatesCertificate() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate certificate = cert.issue(
                "abc123", "tiering-kv-auditor");
        assertThat(certificate.chainDigest()).isEqualTo("abc123");
        assertThat(certificate.issuer())
                .isEqualTo("tiering-kv-auditor");
        assertThat(certificate.keyVersion()).isEqualTo(1);
        assertThat(certificate.signature()).hasSize(64);
    }

    @Test
    void verifyValid() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate certificate = cert.issue(
                "chain-digest-1", "auditor");
        assertThat(cert.verify(certificate)).isTrue();
    }

    @Test
    void verifyTamperedSignatureRejected() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate original = cert.issue("digest", "auditor");
        Certificate tampered = new Certificate(
                original.chainDigest(), original.issuedAt(),
                original.issuer(), "0".repeat(64),
                original.keyVersion());
        assertThat(cert.verify(tampered)).isFalse();
    }

    @Test
    void verifyNullRejected() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        assertThat(cert.verify(null)).isFalse();
    }

    @Test
    void verifyFutureKeyVersionRejected() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate certificate = cert.issue("digest",
                "auditor");
        Certificate future = new Certificate(
                certificate.chainDigest(),
                certificate.issuedAt(),
                certificate.issuer(),
                certificate.signature(), 99);
        assertThat(cert.verify(future)).isFalse();
    }

    @Test
    void rotateKeyIncrements() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        cert.rotateKey();
        assertThat(cert.keyVersion()).isEqualTo(2);
        cert.rotateKey();
        assertThat(cert.keyVersion()).isEqualTo(3);
    }

    @Test
    void revokedKeysTracked() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        cert.rotateKey();
        assertThat(cert.revokedKeys())
                .containsExactly("key-v1");
    }

    @Test
    void certificatesAppendOnly() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        cert.issue("d1", "a");
        cert.issue("d2", "a");
        assertThat(cert.certificates()).hasSize(2);
        assertThatThrownBy(() -> cert.certificates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankChainDigestRejected() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        assertThatThrownBy(() -> cert.issue(" ", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankIssuerRejected() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        assertThatThrownBy(() -> cert.issue("d", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleCertificates() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        for (int i = 0; i < 5; i++) {
            cert.issue("digest-" + i, "auditor");
        }
        List<Certificate> certificates = cert.certificates();
        assertThat(certificates).hasSize(5);
        assertThat(cert.verify(certificates.get(4))).isTrue();
    }

    @Test
    void oldCertificateStillValidAfterRotation() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate old = cert.issue("digest", "auditor");
        cert.rotateKey();
        assertThat(cert.verify(old)).isTrue();
    }

    @ParameterizedTest(name = "digest={0} issuer={1} ver={2}")
    @CsvSource({
            "abc,auditor,1",
            "def,auditor,1",
            "abc123,auditor,1",
            "chain-digest,auditor,1",
            "digest-1,auditor,1",
            "digest-2,auditor,1",
            "digest-3,auditor,1",
            "digest-4,auditor,1",
            "digest-5,auditor,1",
            "x,y,1",
            "long-digest-value,compliance,1",
            "q,regulator,1",
            "r,regulator,1",
            "s,regulator,1",
            "t,regulator,1",
            "u,regulator,1",
            "v,regulator,1",
            "w,regulator,1",
            "x2,regulator,1",
            "y2,regulator,1",
            "z2,regulator,1",
            "a1,b1,1",
            "c1,d1,1",
            "e1,f1,1",
            "g1,h1,1",
            "i1,j1,1",
            "k1,l1,1",
            "m1,n1,1",
            "o1,p1,1",
            "q1,r1,1",
            "s1,t1,1",
            "u1,v1,1",
            "w1,x1,1",
            "y1,z1,1",
            "final,auditor,1"
    })
    void parameterizedIssueVerify(String digest, String issuer,
                                  long version) {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate certificate = cert.issue(digest, issuer);
        assertThat(cert.verify(certificate)).isTrue();
        assertThat(certificate.keyVersion()).isEqualTo(version);
    }

    @ParameterizedTest(name = "digest={0} ver={1} tampered={2}")
    @CsvSource({
            "abc,1,false,true",
            "abc,1,true,false",
            "def,2,false,true",
            "def,1,false,true",
            "ghi,3,false,true",
            "ghi,1,false,true",
            "jkl,4,false,true",
            "jkl,4,true,false",
            "mno,5,false,true",
            "mno,5,true,false",
            "pqr,6,false,true",
            "pqr,6,true,false",
            "stu,7,false,true",
            "stu,7,true,false",
            "vwx,8,false,true",
            "vwx,8,true,false",
            "yza,9,false,true",
            "yza,9,true,false",
            "bcd,10,false,true",
            "bcd,10,true,false"
    })
    void parameterizedVerifyMatrix(String digest, long version,
                                   boolean tampered,
                                   boolean expected) {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate certificate = cert.issue(digest, "auditor");
        if (version != 1) {
            cert.rotateKey();
        }
        if (tampered) {
            certificate = new Certificate(
                    certificate.chainDigest(),
                    certificate.issuedAt(),
                    certificate.issuer(),
                    "0".repeat(64),
                    certificate.keyVersion());
        }
        assertThat(cert.verify(certificate))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "rotations {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            15, 20, 50})
    void parameterizedRotation(int rotations) {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        Certificate first = cert.issue("digest", "auditor");
        for (int i = 0; i < rotations; i++) {
            cert.rotateKey();
        }
        assertThat(cert.keyVersion())
                .isEqualTo(1 + rotations);
        assertThat(cert.revokedKeys())
                .hasSize(rotations);
        assertThat(cert.verify(first)).isTrue();
    }
}
