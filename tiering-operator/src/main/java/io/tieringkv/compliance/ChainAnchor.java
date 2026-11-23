package io.tieringkv.compliance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 链上锚定（ADR-0188）：证明头哈希 → 锚定记录。 */
public final class ChainAnchor {

    private ChainAnchor() {
    }

    /** 锚定记录：链 + 区块 + 时间 + 头哈希 + 锚定哈希。 */
    public record AnchorRecord(String chainId, String blockId,
                               long timestampMillis,
                               String headHash,
                               String anchorHash) {

        public AnchorRecord {
            if (chainId == null || chainId.isBlank()
                    || blockId == null || blockId.isBlank()
                    || headHash == null || headHash.isBlank()) {
                throw new IllegalArgumentException(
                        "chain, block and head required");
            }
        }
    }

    /** 锚定：计算锚定哈希。 */
    public static AnchorRecord anchor(String chainId, String blockId,
                                      long timestampMillis,
                                      String headHash) {
        if (chainId == null || chainId.isBlank()
                || blockId == null || blockId.isBlank()
                || headHash == null || headHash.isBlank()) {
            throw new IllegalArgumentException(
                    "chain, block and head required");
        }
        return new AnchorRecord(chainId, blockId, timestampMillis,
                headHash, hash(chainId, blockId, timestampMillis,
                        headHash));
    }

    /** 重算锚定哈希（用于验证）。 */
    public static String hash(String chainId, String blockId,
                              long timestampMillis,
                              String headHash) {
        String payload = chainId + "|" + blockId + "|"
                + timestampMillis + "|" + headHash;
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", e);
        }
    }
}
