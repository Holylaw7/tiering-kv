package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;

import java.util.List;

/** 第三方证明验证（ADR-0174）：独立校验链与单节点。 */
public final class AttestationVerifier {

    /** 独立验证整条链（不依赖原链状态）。 */
    public boolean verify(List<Attestation> attestations) {
        if (attestations == null) {
            throw new IllegalArgumentException(
                    "attestations required");
        }
        String expectedPrev = "";
        for (Attestation attestation : attestations) {
            if (!attestation.prevHash().equals(expectedPrev)) {
                return false;
            }
            if (!verifyHash(attestation)) {
                return false;
            }
            expectedPrev = attestation.hash();
        }
        return true;
    }

    /** 验证单节点：前序链接 + 哈希重算。 */
    public boolean verify(Attestation attestation,
                          String expectedPrevHash) {
        if (attestation == null) {
            throw new IllegalArgumentException(
                    "attestation required");
        }
        return attestation.prevHash().equals(expectedPrevHash)
                && verifyHash(attestation);
    }

    private static boolean verifyHash(Attestation attestation) {
        String expected = AttestationChain.hash(
                attestation.index(), attestation.regulation(),
                attestation.versionId(), attestation.violations(),
                attestation.prevHash());
        return attestation.hash().equals(expected);
    }
}
