package io.tieringkv.observability;

import io.tieringkv.storage.cold.SSTableMeta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * `INFO sstables` 段落渲染（TD-099）。
 *
 * <p>把冷层每张 SSTable 的元数据暴露给控制面做下钻：id / 键数 / 字节 /
 * 键范围（Base64 编码，兼容二进制键）。该段落**只通过 `INFO sstables` 按需读取**，
 * 不进入全量 INFO，避免表多时把 INFO 撑大。
 */
public final class SstableInfoRenderer {

    private SstableInfoRenderer() {
    }

    public static String render(List<SSTableMeta> tables) {
        StringBuilder out = new StringBuilder(256);
        out.append("# SSTables\r\n");
        // 键范围的第一依赖：比较器语义。Keys.compare 为 Arrays.compareUnsigned，
        // 即无符号字节字典序——"tier:1000000" < "tier:999999"，前端不得按数值重排。
        out.append("sstable_key_order:bytewise\r\n");
        out.append("sstable_count:").append(tables.size()).append("\r\n");
        long totalBytes = tables.stream().mapToLong(SSTableMeta::fileSize).sum();
        long totalKeys = tables.stream().mapToLong(SSTableMeta::entryCount).sum();
        out.append("sstable_total_bytes:").append(totalBytes).append("\r\n");
        out.append("sstable_total_entries:").append(totalKeys).append("\r\n");
        tables.stream()
                .sorted(Comparator.comparingLong(SSTableMeta::id))
                .forEach(table -> out.append("sstable:")
                        .append(table.id())
                        .append("|keys=").append(table.entryCount())
                        .append("|bytes=").append(table.fileSize())
                        .append("|first_b64=").append(b64(table.firstKey()))
                        .append("|last_b64=").append(b64(table.lastKey()))
                        .append("|first_text=").append(escapeKey(table.firstKey()))
                        .append("|last_text=").append(escapeKey(table.lastKey()))
                        .append("\r\n"));
        return out.toString();
    }

    /**
     * 可读文本：可打印 ASCII 原样输出，其余字节转义为 \\xNN，避免二进制键破坏行格式。
     * 该字段仅用于展示，不经受任何排序/比较语义。
     */
    static String escapeKey(byte[] key) {
        if (key == null || key.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(key.length + 8);
        int limit = Math.min(key.length, 64);
        for (int i = 0; i < limit; i++) {
            int b = key[i] & 0xFF;
            if (b >= 0x20 && b < 0x7F && b != '\\' ) {
                out.append((char) b);
            } else if (b == '\\') {
                out.append("\\\\");
            } else {
                out.append(String.format(Locale.ROOT, "\\x%02x", b));
            }
        }
        if (key.length > limit) {
            out.append('…');
        }
        return out.toString();
    }

    private static String b64(byte[] key) {
        if (key == null || key.length == 0) {
            return "";
        }
        return Base64.getEncoder().encodeToString(key);
    }

    /** 便于控制面/测试把 Base64 键还原为可读文本。 */
    public static String decodeKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return "";
        }
        byte[] raw = Base64.getDecoder().decode(base64);
        String text = new String(raw, StandardCharsets.UTF_8);
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    static String human(long bytes) {
        double value = bytes;
        String[] units = {"B", "K", "M", "G", "T"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f%s", value, units[unit]);
    }
}