package io.tieringkv.webconsole.web.dto;

import java.util.List;

/**
 * 冷层 SSTable 清单（TD-099）：来自内核 `INFO sstables` 的按需读取。
 *
 * @param totalBytes  各表字节累加
 * @param totalEntries 各表条目累加（含历史版本）
 */
public record SstableTable(
        long id,
        long keys,
        long bytes,
        String firstKey,
        String lastKey) {

    /** 清单整体视图。 */
    public record View(long count, long totalBytes, long totalEntries, List<SstableTable> tables) {
    }
}
