package io.tieringkv.observability;

import io.tieringkv.storage.cold.Keys;
import io.tieringkv.storage.cold.SSTableMeta;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-099：SSTable 清单段落。 */
class SstableInfoRendererTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static SSTableMeta meta(long id, long keys, long bytes, String first, String last) {
        return new SSTableMeta(id, "0000000" + id + ".sst", keys, bytes,
                first.getBytes(StandardCharsets.UTF_8), last.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rendersEachTableWithTotals() {
        String text = SstableInfoRenderer.render(List.of(
                meta(2, 100, 4096, "b:1", "b:99"),
                meta(1, 50, 2048, "a:1", "a:50")));

        assertThat(text).startsWith("# SSTables\r\n")
                .contains("sstable_count:2\r\n")
                .contains("sstable_total_bytes:6144\r\n")
                .contains("sstable_total_entries:150\r\n");
        // 按 id 升序输出
        assertThat(text.indexOf("sstable:1|")).isLessThan(text.indexOf("sstable:2|"));
        assertThat(text).contains("|keys=50|bytes=2048|first_b64=");
    }

    @Test
    void encodesKeyBoundsAsBase64ForBinarySafety() {
        String text = SstableInfoRenderer.render(List.of(meta(1, 1, 1, "k\u0000x", "z")));

        String b64 = Base64.getEncoder().encodeToString("k\u0000x".getBytes(StandardCharsets.UTF_8));
        assertThat(text).contains("first_b64=" + b64);
        assertThat(SstableInfoRenderer.decodeKey(b64)).isEqualTo("k\u0000x");
    }

    @Test
    void declaresBytewiseOrderAndMatchesEngineComparator() {
        // 内核比较器为 Arrays.compareUnsigned：数字型字符串会呈现"字典序"而非数值序
        assertThat(Keys.compare(bytes("tier:1000000"), bytes("tier:999999"))).isLessThan(0);

        String text = SstableInfoRenderer.render(List.of(
                meta(1, 1, 10, "tier:1000000", "tier:999999")));

        assertThat(text).contains("sstable_key_order:bytewise\r\n");
        assertThat(text).contains("first_text=tier:1000000|last_text=tier:999999");
    }

    @Test
    void escapesNonPrintableBytesInTextBounds() {
        byte[] rawFirst = new byte[] {(byte) 0x00, (byte) 0xFF, 'a', '\\', 0x7F};
        SSTableMeta table = new SSTableMeta(1, "1.sst", 1, 1, rawFirst, rawFirst);

        String text = SstableInfoRenderer.render(List.of(table));

        assertThat(text).contains("first_text=\\x00\\xffa\\\\\\x7f");
        // Base64 边界保持原始字节，可无损还原
        assertThat(text).contains("first_b64=" + Base64.getEncoder().encodeToString(rawFirst));
    }

    @Test
    void escapesLongKeysWithEllipsis() {
        String longKey = "k".repeat(200);
        String escaped = SstableInfoRenderer.escapeKey(bytes(longKey));

        assertThat(escaped).hasSize(65).endsWith("…");
    }

    @Test
    void emptyKeyRangeRendersEmptyText() {
        String text = SstableInfoRenderer.render(List.of(
                new SSTableMeta(1, "1.sst", 0, 0, new byte[0], new byte[0])));

        assertThat(text).contains("first_text=|last_text=");
    }

    @Test
    void emptyEngineRendersHeaderOnly() {
        String text = SstableInfoRenderer.render(List.of());

        assertThat(text).contains("sstable_count:0").doesNotContain("sstable:1|");
    }
}