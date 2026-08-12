package io.tieringkv.compliance;

import io.tieringkv.compliance.ChainAnchor.AnchorRecord;

/** 链验证（ADR-0188）：锚定完整性 + 头哈希匹配。 */
public final class ChainVerifier {

    public boolean verify(AnchorRecord record) {
        if (record == null) {
            throw new IllegalArgumentException(
                    "anchor record required");
        }
        String expected = ChainAnchor.hash(record.chainId(),
                record.blockId(), record.timestampMillis(),
                record.headHash());
        return record.anchorHash().equals(expected);
    }

    public boolean verify(AnchorRecord record,
                          String expectedHeadHash) {
        if (expectedHeadHash == null || expectedHeadHash.isBlank()) {
            throw new IllegalArgumentException(
                    "expected head hash required");
        }
        return record != null
                && record.headHash().equals(expectedHeadHash)
                && verify(record);
    }
}
