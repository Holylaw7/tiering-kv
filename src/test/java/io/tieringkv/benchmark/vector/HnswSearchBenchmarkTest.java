package io.tieringkv.benchmark.vector;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.hnsw.HnswIndex;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** HNSW 图检索基准（ADR-0332）：20K×64 维 P50/P99 + 回归护栏。 */
@Tag("benchmark")
class HnswSearchBenchmarkTest {

    @Test
    void hnswSearchLatency() {
        int count = 20_000;
        int dim = 64;
        Random random = new Random(2024);
        HnswIndex index = new HnswIndex(6);
        List<Embedding> embeddings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = random.nextFloat() * 2 - 1;
            }
            embeddings.add(new Embedding("bench-" + i, values));
        }
        index.build(embeddings);

        // 预热：触发 JIT 内联与类加载，避开构建后 GC 冷启动噪声。
        for (int i = 0; i < 200; i++) {
            index.search(randomQuery(random, dim), 10);
        }
        int rounds = 500;
        long[] samples = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            float[] query = randomQuery(random, dim);
            long start = System.nanoTime();
            assertThat(index.search(query, 10)).hasSize(10);
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        double p50 = samples[rounds / 2] / 1_000_000.0;
        double p99 = samples[(int) (rounds * 0.99)] / 1_000_000.0;
        System.out.printf(Locale.ROOT,
                "PHASE65-BENCH HNSW vectors=%d dim=%d "
                        + "searchP50=%.3fms searchP99=%.3fms%n",
                count, dim, p50, p99);
        // 回归护栏：旧暴力实现约 9.9ms；多层图应显著低于 5ms。
        assertThat(p99).isLessThan(5.0);
    }

    private static float[] randomQuery(Random random, int dim) {
        float[] query = new float[dim];
        for (int d = 0; d < dim; d++) {
            query[d] = random.nextFloat() * 2 - 1;
        }
        return query;
    }
}
