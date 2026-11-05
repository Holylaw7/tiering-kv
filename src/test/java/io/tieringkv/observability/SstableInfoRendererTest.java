package io.tieringkv.observability;

import io.tieringkv.storage.cold.SSTableMeta;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-099：SSTable 清单段落。 */
class SstableInfoRendererTest {

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
    void emptyEngineRendersHeaderOnly() {
        String text = SstableInfoRenderer.render(List.of());

        assertThat(text).contains("sstable_count:0").doesNotContain("sstable:1|");
    }
}
