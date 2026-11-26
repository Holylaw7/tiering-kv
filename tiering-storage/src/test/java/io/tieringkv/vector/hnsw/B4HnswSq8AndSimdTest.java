package io.tieringkv.vector.hnsw;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * B4 HNSW 优化验证：可插拔 FMA/SIMD 距离核、SQ8 标量量化（-75% 向量内存）、
 * 两阶段精确重排（SQ8_RERANK）、TKHN v1/v2 兼容性与多线程搜索上下文隔离。
 */
class B4HnswSq8AndSimdTest {

    private static List<Embedding> randomVectors(int count, int dim, long seed) {
        Random random = new Random(seed);
        List<Embedding> embeddings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = random.nextFloat() * 2.0f - 1.0f;
            }
            embeddings.add(new Embedding("v" + i, values));
        }
        return embeddings;
    }

    @Test
    void scalarFmaKernelMatchesReferenceAcrossUnalignedAndAlignedDims() {
        VectorDistanceKernel kernel = VectorDistanceKernel.active();
        assertThat(kernel.name()).isNotBlank();
        assertThat(VectorDistanceKernel.panamaPreferredBitSize()).isGreaterThanOrEqualTo(0);

        int[] dims = {0, 1, 2, 3, 4, 7, 15, 16, 63, 64, 128, 384};
        Random rng = new Random(42);
        for (int dim : dims) {
            float[] a = new float[dim];
            float[] b = new float[dim];
            byte[] qa = new byte[dim];
            byte[] qb = new byte[dim];
            double expectedFloat = 0.0;
            double expectedFloatSq8 = 0.0;
            int expectedInt = 0;
            for (int i = 0; i < dim; i++) {
                a[i] = rng.nextFloat() * 2.0f - 1.0f;
                b[i] = rng.nextFloat() * 2.0f - 1.0f;
                qa[i] = (byte) (rng.nextInt(255) - 127);
                qb[i] = (byte) (rng.nextInt(255) - 127);
                expectedFloat += (double) a[i] * b[i];
                expectedFloatSq8 += (double) a[i] * qb[i];
                expectedInt += qa[i] * qb[i];
            }
            assertThat((double) kernel.dotProduct(a, b))
                    .isCloseTo(expectedFloat, within(1e-3));
            assertThat((double) kernel.dotProductFloatSq8(a, qb))
                    .isCloseTo(expectedFloatSq8, within(1e-2));
            assertThat(kernel.dotProductSq8(qa, qb)).isEqualTo(expectedInt);
        }
    }

    @Test
    void scalarQuantizerSq8PreservesCosineAccuracyAndCutsMemory() {
        Random rng = new Random(123);
        int dim = 384;
        float[] u1 = normalize(randomArray(rng, dim));
        float[] u2 = normalize(randomArray(rng, dim));

        ScalarQuantizerSq8.QuantizedVector q1 = ScalarQuantizerSq8.quantizeUnit(u1);
        ScalarQuantizerSq8.QuantizedVector q2 = ScalarQuantizerSq8.quantizeUnit(u2);
        assertThat(q1.dimension()).isEqualTo(dim);
        assertThat(q1.scale()).isPositive();

        byte[] scratch = new byte[dim];
        float scratchScale = ScalarQuantizerSq8.quantizeUnitInto(u1, scratch);
        assertThat(scratchScale).isEqualTo(q1.scale());
        assertThat(scratch).isEqualTo(q1.copyValues());

        VectorDistanceKernel kernel = VectorDistanceKernel.active();
        float exactDot = kernel.dotProduct(u1, u2);
        float asymDot = q2.scale() * kernel.dotProductFloatSq8(u1, q2.copyValues());
        float symDot = (q1.scale() * q2.scale()) * kernel.dotProductSq8(q1.copyValues(), q2.copyValues());
        assertThat((double) asymDot).isCloseTo(exactDot, within(0.01));
        assertThat((double) symDot).isCloseTo(exactDot, within(0.015));

        ScalarQuantizerSq8.QuantizedVector zero = ScalarQuantizerSq8.quantizeUnit(new float[dim]);
        assertThat(zero.scale()).isZero();
        assertThat(ScalarQuantizerSq8.quantizeUnit(new float[0]).dimension()).isZero();

        List<Embedding> vectors = randomVectors(1_000, 384, 77);
        HnswIndex fp32 = new HnswIndex(5, HnswIndex.QuantizationMode.NONE);
        fp32.build(vectors);
        HnswIndex sq8 = new HnswIndex(5, HnswIndex.QuantizationMode.SQ8);
        sq8.build(vectors);

        double ratio = sq8.estimatedVectorBytes() / (double) fp32.estimatedVectorBytes();
        assertThat(ratio).as("SQ8 vs FP32 vector bytes ratio (384D)").isLessThan(0.28);
    }

    @Test
    void sq8AndSq8RerankRecallAndMemoryOn2k64d() throws Exception {
        int count = 2_000;
        int dim = 64;
        int topK = 10;
        List<Embedding> embeddings = randomVectors(count, dim, 2026);
        VectorStore brute = new VectorStore();
        embeddings.forEach(brute::put);

        HnswIndex fp32 = new HnswIndex(6, HnswIndex.QuantizationMode.NONE);
        fp32.build(embeddings);
        HnswIndex sq8 = new HnswIndex(6, HnswIndex.QuantizationMode.SQ8);
        sq8.build(embeddings);
        HnswIndex sq8Rerank = new HnswIndex(6, HnswIndex.QuantizationMode.SQ8_RERANK);
        sq8Rerank.build(embeddings);

        Random queryRng = new Random(999);
        int queries = 40;
        long hitsFp32 = 0;
        long hitsSq8 = 0;
        long hitsRerank = 0;
        for (int q = 0; q < queries; q++) {
            float[] query = randomArray(queryRng, dim);
            Set<String> exact = brute.search(query, topK).stream()
                    .map(VectorStore.ScoredEmbedding::id)
                    .collect(Collectors.toSet());
            hitsFp32 += overlapHits(fp32.search(query, topK), exact);
            hitsSq8 += overlapHits(sq8.search(query, topK), exact);
            hitsRerank += overlapHits(sq8Rerank.search(query, topK), exact);
        }
        double totalPossible = (double) queries * topK;
        double recallFp32 = hitsFp32 / totalPossible;
        double recallSq8 = hitsSq8 / totalPossible;
        double recallRerank = hitsRerank / totalPossible;

        // Also validate high-recall profile (efSearch=64): SQ8_RERANK >= 0.95
        HnswIndex sq8RerankHi = new HnswIndex(6, HnswIndex.DEFAULT_M, HnswIndex.DEFAULT_MMAX,
                HnswIndex.DEFAULT_EF_CONSTRUCTION, 64, HnswIndex.QuantizationMode.SQ8_RERANK);
        sq8RerankHi.build(embeddings);
        long hitsRerankHi = 0;
        Random queryRngHi = new Random(999);
        for (int q = 0; q < queries; q++) {
            float[] query = randomArray(queryRngHi, dim);
            Set<String> exact = brute.search(query, topK).stream()
                    .map(VectorStore.ScoredEmbedding::id)
                    .collect(Collectors.toSet());
            hitsRerankHi += overlapHits(sq8RerankHi.search(query, topK), exact);
        }
        double recallRerankHi = hitsRerankHi / totalPossible;

        byte[] v1Bytes = fp32.serialize();
        byte[] v2Sq8Bytes = sq8.serialize();
        double memReduction = 1.0 - (sq8.estimatedVectorBytes() / (double) fp32.estimatedVectorBytes());

        System.out.printf(Locale.ROOT,
                "B4-HNSW-VERIFY count=%d dim=%d recallFP32=%.4f recallSQ8=%.4f recallSQ8Rerank=%.4f "
                        + "recallSQ8RerankEf64=%.4f vecBytesFP32=%d vecBytesSQ8=%d vecMemReduction=%.1f%% "
                        + "serBytesV1=%d serBytesV2SQ8=%d%n",
                count, dim, recallFp32, recallSq8, recallRerank, recallRerankHi,
                fp32.estimatedVectorBytes(), sq8.estimatedVectorBytes(), memReduction * 100.0,
                v1Bytes.length, v2Sq8Bytes.length);

        assertThat(recallFp32).isGreaterThanOrEqualTo(0.92);
        assertThat(recallSq8).isGreaterThanOrEqualTo(0.92);
        assertThat(recallRerank).isGreaterThanOrEqualTo(0.94);
        assertThat(recallRerankHi).isGreaterThanOrEqualTo(0.95);
        assertThat(memReduction).isGreaterThanOrEqualTo(0.68);
        assertThat(v2Sq8Bytes.length).isLessThan(v1Bytes.length);
    }

    @Test
    void tkhnV2RoundTripAndV1BackwardCompatibility() throws Exception {
        List<Embedding> embeddings = randomVectors(500, 64, 55);

        HnswIndex fp32 = new HnswIndex(5, HnswIndex.QuantizationMode.NONE);
        fp32.build(embeddings);
        byte[] v1Bytes = fp32.serialize();
        // Verify v1 format header magic ("TKHN") + version == 1
        ByteBuffer v1Header = ByteBuffer.wrap(v1Bytes, 0, 8);
        assertThat((char) v1Header.get()).isEqualTo('T');
        assertThat((char) v1Header.get()).isEqualTo('K');
        assertThat((char) v1Header.get()).isEqualTo('H');
        assertThat((char) v1Header.get()).isEqualTo('N');
        assertThat(v1Header.getInt()).isEqualTo(1);
        HnswIndex restoredV1 = HnswIndex.deserialize(v1Bytes);
        assertThat(restoredV1.quantizationMode()).isEqualTo(HnswIndex.QuantizationMode.NONE);
        assertThat(restoredV1.size()).isEqualTo(fp32.size());
        assertThat(restoredV1.edgeCount()).isEqualTo(fp32.edgeCount());

        for (HnswIndex.QuantizationMode mode : List.of(
                HnswIndex.QuantizationMode.SQ8, HnswIndex.QuantizationMode.SQ8_RERANK)) {
            HnswIndex index = new HnswIndex(5, mode);
            index.build(embeddings);
            byte[] v2Bytes = index.serialize();
            assertThat(ByteBuffer.wrap(v2Bytes, 4, 4).getInt()).isEqualTo(2);

            HnswIndex restored = HnswIndex.deserialize(v2Bytes);
            assertThat(restored.quantizationMode()).isEqualTo(mode);
            assertThat(restored.size()).isEqualTo(index.size());
            assertThat(restored.edgeCount()).isEqualTo(index.edgeCount());

            Random qRng = new Random(88);
            for (int q = 0; q < 10; q++) {
                float[] query = randomArray(qRng, 64);
                List<String> before = index.search(query, 10).stream()
                        .map(VectorStore.ScoredEmbedding::id).toList();
                List<String> after = restored.search(query, 10).stream()
                        .map(VectorStore.ScoredEmbedding::id).toList();
                assertThat(after).isEqualTo(before);
            }
        }

        // Invalid version rejected cleanly
        byte[] badVersion = v1Bytes.clone();
        badVersion[7] = 99;
        assertThatThrownBy(() -> HnswIndex.deserialize(badVersion))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported HNSW index version");
    }

    @Test
    void concurrentMultiThreadSearchMatchesSingleThreadDeterministically() throws Exception {
        List<Embedding> embeddings = randomVectors(1_200, 64, 321);
        HnswIndex sq8 = new HnswIndex(6, HnswIndex.QuantizationMode.SQ8);
        sq8.build(embeddings);
        HnswIndex rerank = new HnswIndex(6, HnswIndex.QuantizationMode.SQ8_RERANK);
        rerank.build(embeddings);

        Random rng = new Random(654);
        int queryCount = 32;
        float[][] queries = new float[queryCount][];
        List<List<String>> expectedSq8 = new ArrayList<>(queryCount);
        List<List<String>> expectedRerank = new ArrayList<>(queryCount);
        for (int i = 0; i < queryCount; i++) {
            queries[i] = randomArray(rng, 64);
            expectedSq8.add(sq8.search(queries[i], 10).stream()
                    .map(VectorStore.ScoredEmbedding::id).toList());
            expectedRerank.add(rerank.search(queries[i], 10).stream()
                    .map(VectorStore.ScoredEmbedding::id).toList());
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    for (int round = 0; round < 25; round++) {
                        for (int q = 0; q < queryCount; q++) {
                            List<String> gotSq8 = sq8.search(queries[q], 10).stream()
                                    .map(VectorStore.ScoredEmbedding::id).toList();
                            assertThat(gotSq8).isEqualTo(expectedSq8.get(q));
                            List<String> gotRerank = rerank.search(queries[q], 10).stream()
                                    .map(VectorStore.ScoredEmbedding::id).toList();
                            assertThat(gotRerank).isEqualTo(expectedRerank.get(q));
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void vectorIndexStoreAndCustomDistanceKernelIntegration() {
        VectorIndexStore store = new VectorIndexStore(5);
        for (Embedding e : randomVectors(400, 32, 17)) {
            store.put(e);
        }
        HnswIndex rebuiltSq8 = store.rebuildIndex(HnswIndex.QuantizationMode.SQ8);
        assertThat(rebuiltSq8.quantizationMode()).isEqualTo(HnswIndex.QuantizationMode.SQ8);
        assertThat(rebuiltSq8.size()).isEqualTo(400);
        assertThat(rebuiltSq8.search(randomArray(new Random(1), 32), 5)).hasSize(5);

        AtomicInteger floatSq8Calls = new AtomicInteger();
        VectorDistanceKernel delegate = VectorDistanceKernel.scalarFma();
        VectorDistanceKernel instrumented = new VectorDistanceKernel() {
            @Override
            public String name() {
                return "instrumented-test";
            }

            @Override
            public float dotProduct(float[] a, float[] b) {
                return delegate.dotProduct(a, b);
            }

            @Override
            public float dotProductFloatSq8(float[] query, byte[] quantized) {
                floatSq8Calls.incrementAndGet();
                return delegate.dotProductFloatSq8(query, quantized);
            }

            @Override
            public int dotProductSq8(byte[] a, byte[] b) {
                return delegate.dotProductSq8(a, b);
            }
        };

        HnswIndex custom = new HnswIndex(5, HnswIndex.DEFAULT_M, HnswIndex.DEFAULT_MMAX,
                HnswIndex.DEFAULT_EF_CONSTRUCTION, HnswIndex.DEFAULT_EF_SEARCH,
                HnswIndex.QuantizationMode.SQ8, instrumented);
        custom.build(randomVectors(350, 32, 19));
        assertThat(custom.distanceKernel().name()).isEqualTo("instrumented-test");
        assertThat(custom.search(randomArray(new Random(2), 32), 5)).hasSize(5);
        assertThat(floatSq8Calls.get()).isGreaterThan(100);
    }

    private static long overlapHits(List<VectorStore.ScoredEmbedding> actual,
                                    Set<String> exact) {
        return actual.stream()
                .map(VectorStore.ScoredEmbedding::id)
                .filter(exact::contains)
                .count();
    }

    private static float[] randomArray(Random rng, int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = rng.nextFloat() * 2.0f - 1.0f;
        }
        return v;
    }

    private static float[] normalize(float[] v) {
        double normSq = 0.0;
        for (float x : v) {
            normSq += (double) x * x;
        }
        float inv = (float) (1.0 / Math.sqrt(normSq));
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] * inv;
        }
        return out;
    }
}
