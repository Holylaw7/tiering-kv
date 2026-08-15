package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 冲突策略抽象（ADR-0333）：接口可插拔 + LWW 实现。 */
class ConflictResolverInterfaceTest {

    private static ChangeEvent put(String key, String value) {
        return new ChangeEvent(1, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8), false,
                "t1", "r1", 100);
    }

    @Test
    void lwwResolverImplementsInterface() {
        assertThat(new LwwConflictResolver())
                .isInstanceOf(ConflictResolver.class);
    }

    @Test
    void sinkAcceptsCustomResolver() {
        ConflictResolver rejectAll = (event, origin) -> false;
        MemTable storage = MemTable.create();
        CrossClusterSink sink = new CrossClusterSink(storage,
                rejectAll);
        assertThat(sink.apply(put("k", "v"), "cluster-a")).isFalse();
        assertThat(storage.get("k".getBytes(
                StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void sinkAcceptsCustomResolverWithWatermark() throws Exception {
        ConflictResolver acceptAll = (event, origin) -> true;
        MemTable storage = MemTable.create();
        CrossClusterWatermark watermark = new CrossClusterWatermark(
                java.nio.file.Files.createTempDirectory("resolver-wm")
                        .resolve("wm.bin"));
        try {
            CrossClusterSink sink = new CrossClusterSink(storage,
                    acceptAll, watermark);
            assertThat(sink.apply(put("k", "v"), "cluster-a")).isTrue();
            assertThat(storage.get("k".getBytes(
                    StandardCharsets.UTF_8)))
                    .isEqualTo("v".getBytes(StandardCharsets.UTF_8));
        } finally {
            watermark.close();
        }
    }
}
