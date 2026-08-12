package io.tieringkv.compliance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 合规持续证明（ADR-0167）：哈希链 attestation + 验证。 */
public final class AttestationChain {

    /** 证明节点：索引 + 内容 + 前序哈希 + 自身哈希。 */
    public record Attestation(int index, String regulation,
                              String versionId, int violations,
                              String prevHash, String hash,
                              long timestampMillis) {

        public Attestation {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "index must be non-negative");
            }
            if (regulation == null || regulation.isBlank()) {
                throw new IllegalArgumentException(
                        "regulation required");
            }
            if (versionId == null || versionId.isBlank()) {
                throw new IllegalArgumentException(
                        "versionId required");
            }
            if (violations < 0) {
                throw new IllegalArgumentException(
                        "violations must be non-negative");
            }
        }
    }

    private final List<Attestation> chain =
            new CopyOnWriteArrayList<>();

    public AttestationChain() {
    }

    /** 重建链：用给定证明列表构造（用于验证/审计恢复）。 */
    public AttestationChain(List<Attestation> initial) {
        if (initial != null) {
            chain.addAll(initial);
        }
    }

    /** 追加证明：链接到链尾。 */
    public synchronized Attestation append(
            String regulation, String versionId,
            int violations, long timestampMillis) {
        if (regulation == null || regulation.isBlank()
                || versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException(
                    "regulation and versionId required");
        }
        if (violations < 0) {
            throw new IllegalArgumentException(
                    "violations must be non-negative");
        }
        String prevHash = chain.isEmpty()
                ? "" : chain.get(chain.size() - 1).hash();
        int index = chain.size();
        Attestation attestation = new Attestation(index,
                regulation, versionId, violations, prevHash,
                hash(index, regulation, versionId, violations,
                        prevHash), timestampMillis);
        chain.add(attestation);
        return attestation;
    }

    /** 从审计运行记录追加。 */
    public synchronized Attestation append(
            ContinuousAuditPipeline.AuditRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run required");
        }
        return append(run.regulation(), run.versionId(),
                run.violations(), run.ranAtMillis());
    }

    /** 验证整条链：prevHash 链接 + 哈希重算。 */
    public boolean verify() {
        String expectedPrev = "";
        for (Attestation attestation : chain) {
            if (!attestation.prevHash().equals(expectedPrev)) {
                return false;
            }
            String expectedHash = hash(attestation.index(),
                    attestation.regulation(),
                    attestation.versionId(),
                    attestation.violations(),
                    attestation.prevHash());
            if (!attestation.hash().equals(expectedHash)) {
                return false;
            }
            expectedPrev = attestation.hash();
        }
        return true;
    }

    public int size() {
        return chain.size();
    }

    public List<Attestation> attestations() {
        return List.copyOf(chain);
    }

    public static String hash(int index, String regulation,
                              String versionId, int violations,
                              String prevHash) {
        String payload = index + "|" + regulation + "|"
                + versionId + "|" + violations + "|" + prevHash;
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", e);
        }
    }
}
