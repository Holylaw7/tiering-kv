package io.tieringkv.storage.cold;

import io.tieringkv.storage.cold.filter.BloomFilter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterTest {

    @Test
    void falsePositiveRateBelowOnePercent() {
        int insertions = 100_000;
        BloomFilter filter = new BloomFilter(insertions, 10);
        for (int i = 0; i < insertions; i++) {
            filter.put(key("key-%05d", i));
        }
        int falsePositives = 0;
        int probes = 1_000_000;
        for (int i = 0; i < probes; i++) {
            if (filter.mightContain(key("absent-%06d", i))) {
                falsePositives++;
            }
        }
        double rate = falsePositives / (double) probes;
        System.out.printf(Locale.ROOT, "bloom false positive rate=%.4f%%%n", rate * 100);
        assertThat(rate).isLessThan(0.01);
    }

    @Test
    void serializeRoundTrip() {
        BloomFilter filter = new BloomFilter(1000, 10);
        filter.put("a".getBytes(StandardCharsets.UTF_8));
        filter.put("b".getBytes(StandardCharsets.UTF_8));
        BloomFilter restored = BloomFilter.deserialize(filter.serialize());
        assertThat(restored).isEqualTo(filter);
        assertThat(restored.mightContain("a".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(restored.mightContain("b".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void emptyFilterRejectsEverything() {
        BloomFilter filter = new BloomFilter(100, 10);
        assertThat(filter.mightContain("x".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    private static byte[] key(String format, int value) {
        return String.format(Locale.ROOT, format, value).getBytes(StandardCharsets.UTF_8);
    }
}
