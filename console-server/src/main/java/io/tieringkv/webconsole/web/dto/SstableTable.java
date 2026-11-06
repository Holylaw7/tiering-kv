package io.tieringkv.webconsole.web.dto;

import java.util.List;

/**
 * 冷层 SSTable 清单（TD-099/TD-100）：来自内核 `INFO sstables` 的按需读取。
 *
 * <p>`keyOrder` 描述键范围遵循的比较器语义（当前实现恒为 `bytewise`，
 * 即无符号字节字典序）。前端**不得**按数值或本地化顺序重新解释这些键。
 *
 * <p>键范围同时提供两种形态：`firstKey/lastKey` 为转义后的可读文本
 * （非可打印字节显示为 \\xNN），`firstKeyBase64/lastKeyBase64` 为无损原始字节。
 */
public record SstableTable(
        long id,
        long keys,
        long bytes,
        String firstKey,
        String lastKey,
        String firstKeyBase64,
        String lastKeyBase64) {

    /** 清单整体视图。 */
    public record View(String keyOrder, long count, long totalBytes, long totalEntries,
                       List<SstableTable> tables) {
    }
}
