package io.tieringkv.compliance;

import io.tieringkv.compliance.ChainAnchor.AnchorRecord;

import java.util.Collection;

/** 跨链验证（ADR-0195）：任一有效链 + 多链一致性。 */
public final class CrossChainVerifier {

    private final ChainVerifier verifier = new ChainVerifier();

    /** 任一链有效即可（审计方自由选择链）。 */
    public boolean verifyAny(Collection<AnchorRecord> records) {
        if (records == null || records.isEmpty()) {
            return false;
        }
        return records.stream().anyMatch(verifier::verify);
    }

    /** 多链一致性：全部有效且头哈希一致。 */
    public boolean verifyConsistent(Collection<AnchorRecord> records) {
        if (records == null || records.isEmpty()) {
            return false;
        }
        String head = null;
        for (AnchorRecord record : records) {
            if (!verifier.verify(record)) {
                return false;
            }
            if (head == null) {
                head = record.headHash();
            } else if (!head.equals(record.headHash())) {
                return false;
            }
        }
        return true;
    }
}
