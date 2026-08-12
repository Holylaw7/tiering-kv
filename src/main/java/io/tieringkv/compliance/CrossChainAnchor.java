package io.tieringkv.compliance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 跨链锚定（ADR-0195）：同头哈希多链锚定。 */
public final class CrossChainAnchor {

    private final Map<String, ChainAnchor.AnchorRecord> anchors =
            new ConcurrentHashMap<>();

    /** 锚定到指定链：返回该链锚定记录。 */
    public ChainAnchor.AnchorRecord anchor(String chainId,
                                           String blockId,
                                           long timestampMillis,
                                           String headHash) {
        ChainAnchor.AnchorRecord record = ChainAnchor.anchor(
                chainId, blockId, timestampMillis, headHash);
        anchors.put(chainId, record);
        return record;
    }

    /** 锚定到多条链（同一头哈希）。 */
    public Map<String, ChainAnchor.AnchorRecord> anchorAll(
            Set<String> chainIds, long timestampMillis,
            String headHash) {
        if (chainIds == null || chainIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "chainIds required");
        }
        Map<String, ChainAnchor.AnchorRecord> result =
                new LinkedHashMap<>();
        int block = 0;
        for (String chainId : chainIds) {
            result.put(chainId, anchor(chainId,
                    "block-" + block++, timestampMillis,
                    headHash));
        }
        return result;
    }

    public ChainAnchor.AnchorRecord record(String chainId) {
        ChainAnchor.AnchorRecord record = anchors.get(chainId);
        if (record == null) {
            throw new IllegalArgumentException(
                    "no anchor for chain " + chainId);
        }
        return record;
    }

    public Set<String> chainIds() {
        return Set.copyOf(anchors.keySet());
    }

    public int size() {
        return anchors.size();
    }
}
