package io.tieringkv.benchmark;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.EvictionPolicy;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.SampledLfuPolicy;
import io.tieringkv.storage.cache.TierMigration;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.hnsw.HnswIndex;
import io.tieringkv.vector.hnsw.VectorDistanceKernel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path B (v6.3.0) 全栈可复现内核性能基准：
 * <ol>
 *   <li>B1 + B2：真实 TCP RESP 端到端吞吐矩阵（{@code c=1/50/200, P=1} 与 {@code c=50, P=16}）；</li>
 *   <li>B2：63 万键 × 64B JVM 存活堆占用与 8 线程并发读吞吐（{@code SampledLfuPolicy} vs 旧 {@code LFUPolicy}）；</li>
 *   <li>B4：HNSW 20K×64D 与 10K×128D 矩阵（{@code NONE} vs {@code SQ8} vs {@code SQ8_RERANK}）。</li>
 * </ol>
 */
@Tag("benchmark")
class B4KernelOptimizationBenchmarkTest {

    @Test
    void b1AndB2ServerThroughputMatrix() throws Exception {
        MemoryManager memory = new MemoryManager(2L << 30);
        MemTable memTable = MemTable.create(memory);
        byte[] value64 = new byte[64];
        Arrays.fill(value64, (byte) 'v');
        int keySpace = 100_000;
        for (int i = 0; i < keySpace; i++) {
            memTable.put(keyBytes(i), value64);
        }
        SampledLfuPolicy policy = new SampledLfuPolicy(memTable, 60_000L);
        EvictionManager eviction = new EvictionManager(memTable, memory, policy, TierMigration.discard());
        TrackingStorageEngine engine = new TrackingStorageEngine(memTable, eviction);

        try (KeyShardExecutor executor = new KeyShardExecutor(16, "pathb-net")) {
            TieringKvServer server = new TieringKvServer(
                    new ServerConfig("127.0.0.1", 0),
                    new CommandEngine(CommandRegistry.createDefault(), engine, executor));
            server.start();
            try {
                int port = server.boundPort();
                // Warmup JIT across both SET and GET paths
                runClientMatrix(port, 16, 16, 40_000, keySpace, value64, true);
                runClientMatrix(port, 16, 16, 40_000, keySpace, value64, false);

                BenchResult setC1 = runClientMatrix(port, 1, 1, 20_000, keySpace, value64, true);
                BenchResult getC1 = runClientMatrix(port, 1, 1, 20_000, keySpace, value64, false);
                BenchResult setC50 = runClientMatrix(port, 50, 1, 120_000, keySpace, value64, true);
                BenchResult getC50 = runClientMatrix(port, 50, 1, 120_000, keySpace, value64, false);
                BenchResult setC200 = runClientMatrix(port, 200, 1, 120_000, keySpace, value64, true);
                BenchResult getC200 = runClientMatrix(port, 200, 1, 120_000, keySpace, value64, false);
                BenchResult setP16 = runClientMatrix(port, 50, 16, 320_000, keySpace, value64, true);
                BenchResult getP16 = runClientMatrix(port, 50, 16, 320_000, keySpace, value64, false);

                printNet("SET c=1 P=1", setC1);
                printNet("GET c=1 P=1", getC1);
                printNet("SET c=50 P=1", setC50);
                printNet("GET c=50 P=1", getC50);
                printNet("SET c=200 P=1", setC200);
                printNet("GET c=200 P=1", getC200);
                printNet("SET c=50 P=16", setP16);
                printNet("GET c=50 P=16", getP16);

                assertThat(getP16.rps).isGreaterThan(150_000.0);
                assertThat(setP16.rps).isGreaterThan(100_000.0);
            } finally {
                server.shutdown();
            }
        }
    }

    @Test
    void b2HeapFootprint630kKeysAndConcurrentReadComparison() throws Exception {
        int keyCount = 630_000;
        byte[] value64 = new byte[64];
        Arrays.fill(value64, (byte) 'x');

        HeapAndReadResult sampled = measureHeapAndConcurrentRead(keyCount, value64, false);
        HeapAndReadResult legacy = measureHeapAndConcurrentRead(keyCount, value64, true);

        double heapReductionPct = (1.0 - (sampled.liveHeapBytes / (double) legacy.liveHeapBytes)) * 100.0;
        double readSpeedup = sampled.concurrentReadOps / legacy.concurrentReadOps;

        System.out.printf(Locale.ROOT,
                "PATHB-B2-HEAP keys=%d valBytes=64 "
                        + "sampledHeapMB=%.1f legacyHeapMB=%.1f heapReduction=%.1f%% "
                        + "sampledBytesPerKey=%.1f legacyBytesPerKey=%.1f "
                        + "sampledReadOps=%.0f legacyReadOps=%.0f readSpeedup=%.2fx%n",
                keyCount,
                sampled.liveHeapBytes / (1024.0 * 1024.0),
                legacy.liveHeapBytes / (1024.0 * 1024.0),
                heapReductionPct,
                sampled.liveHeapBytes / (double) keyCount,
                legacy.liveHeapBytes / (double) keyCount,
                sampled.concurrentReadOps,
                legacy.concurrentReadOps,
                readSpeedup);

        assertThat(sampled.liveHeapBytes).isLessThan(legacy.liveHeapBytes);
        assertThat(sampled.concurrentReadOps).isGreaterThan(legacy.concurrentReadOps);
    }

    @Test
    void b4Hnsw20k64dAnd10k128dMatrix() throws Exception {
        System.out.printf(Locale.ROOT,
                "PATHB-HNSW-ENV kernel=%s panamaPresent=%s panamaBits=%d cpus=%d java=%s%n",
                VectorDistanceKernel.active().name(),
                VectorDistanceKernel.isPanamaVectorModulePresent(),
                VectorDistanceKernel.panamaPreferredBitSize(),
                Runtime.getRuntime().availableProcessors(),
                System.getProperty("java.version"));

        runHnswMatrix(20_000, 64, 500, 30);
        runHnswMatrix(10_000, 128, 300, 20);
    }

    private static void runHnswMatrix(int count, int dim, int latencyRounds, int recallQueries)
            throws Exception {
        List<Embedding> embeddings = randomEmbeddings(count, dim, 2024L + dim);
        VectorStore brute = new VectorStore();
        embeddings.forEach(brute::put);

        Random queryRng = new Random(777L + dim);
        float[][] recallQ = new float[recallQueries][];
        List<Set<String>> exactSets = new ArrayList<>(recallQueries);
        for (int i = 0; i < recallQueries; i++) {
            recallQ[i] = randomVec(queryRng, dim);
            exactSets.add(brute.search(recallQ[i], 10).stream()
                    .map(VectorStore.ScoredEmbedding::id)
                    .collect(Collectors.toSet()));
        }

        record Profile(String label, HnswIndex.QuantizationMode mode, int efSearch) {
        }
        List<Profile> profiles = List.of(
                new Profile("NONE-ef48", HnswIndex.QuantizationMode.NONE, 48),
                new Profile("SQ8-ef48", HnswIndex.QuantizationMode.SQ8, 48),
                new Profile("SQ8_RERANK-ef48", HnswIndex.QuantizationMode.SQ8_RERANK, 48),
                new Profile("SQ8_RERANK-ef64", HnswIndex.QuantizationMode.SQ8_RERANK, 64));

        long baselineVecBytes = 0;
        for (Profile p : profiles) {
            HnswIndex index = new HnswIndex(6, HnswIndex.DEFAULT_M, HnswIndex.DEFAULT_MMAX,
                    HnswIndex.DEFAULT_EF_CONSTRUCTION, p.efSearch(), p.mode());
            long buildStart = System.nanoTime();
            index.build(embeddings);
            double buildMs = (System.nanoTime() - buildStart) / 1_000_000.0;

            Random benchRng = new Random(999L + dim);
            for (int i = 0; i < 200; i++) {
                index.search(randomVec(benchRng, dim), 10);
            }
            long[] samples = new long[latencyRounds];
            long totalStart = System.nanoTime();
            for (int i = 0; i < latencyRounds; i++) {
                float[] q = randomVec(benchRng, dim);
                long t0 = System.nanoTime();
                assertThat(index.search(q, 10)).hasSize(10);
                samples[i] = System.nanoTime() - t0;
            }
            long totalNanos = System.nanoTime() - totalStart;
            Arrays.sort(samples);
            double p50 = samples[latencyRounds / 2] / 1_000_000.0;
            double p99 = samples[(int) (latencyRounds * 0.99)] / 1_000_000.0;
            double qps = latencyRounds / (totalNanos / 1_000_000_000.0);

            long hits = 0;
            for (int i = 0; i < recallQueries; i++) {
                Set<String> exact = exactSets.get(i);
                hits += index.search(recallQ[i], 10).stream()
                        .map(VectorStore.ScoredEmbedding::id)
                        .filter(exact::contains)
                        .count();
            }
            double recall10 = hits / (double) (recallQueries * 10);
            long vecBytes = index.estimatedVectorBytes();
            if (baselineVecBytes == 0) {
                baselineVecBytes = vecBytes;
            }
            double vecReduction = (1.0 - (vecBytes / (double) baselineVecBytes)) * 100.0;
            int serBytes = index.serialize().length;

            System.out.printf(Locale.ROOT,
                    "PATHB-HNSW-BENCH vectors=%d dim=%d profile=%s buildMs=%.0f "
                            + "p50=%.3fms p99=%.3fms qps=%.0f recall10=%.4f "
                            + "vecBytes=%d vecMemDelta=%.1f%% serBytes=%d%n",
                    count, dim, p.label(), buildMs, p50, p99, qps, recall10,
                    vecBytes, -vecReduction, serBytes);

            assertThat(p99).isLessThan(5.0);
        }
    }

    private static HeapAndReadResult measureHeapAndConcurrentRead(
            int keyCount, byte[] value64, boolean legacyLfu) throws Exception {
        long before = settledUsedHeap();
        MemoryManager memory = new MemoryManager(2L << 30);
        MemTable memTable = MemTable.create(memory);
        EvictionPolicy policy = legacyLfu
                ? new LFUPolicy(60_000L)
                : new SampledLfuPolicy(memTable, 60_000L);
        EvictionManager eviction = new EvictionManager(memTable, memory, policy, TierMigration.discard());
        TrackingStorageEngine engine = new TrackingStorageEngine(memTable, eviction);

        for (int i = 0; i < keyCount; i++) {
            engine.put(keyBytes(i), value64);
        }
        long after = settledUsedHeap();
        long deltaHeap = Math.max(1L, after - before);

        // 8-thread concurrent GET benchmark (800k total reads)
        int threads = 8;
        int opsPerThread = 100_000;
        byte[][] sampleKeys = new byte[4096][];
        for (int i = 0; i < sampleKeys.length; i++) {
            sampleKeys[i] = keyBytes((i * 151) % keyCount);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                tasks.add(() -> {
                    start.await();
                    int sum = 0;
                    int idx = seed * 37;
                    for (int i = 0; i < opsPerThread; i++) {
                        byte[] v = engine.get(sampleKeys[(idx + i) & 4095]);
                        if (v != null) {
                            sum += v.length;
                        }
                    }
                    return sum;
                });
            }
            long t0 = System.nanoTime();
            List<Future<Integer>> futures = new ArrayList<>(threads);
            for (Callable<Integer> c : tasks) {
                futures.add(pool.submit(c));
            }
            start.countDown();
            long checkSum = 0;
            for (Future<Integer> f : futures) {
                checkSum += f.get();
            }
            long elapsedNanos = System.nanoTime() - t0;
            assertThat(checkSum).isPositive();
            double opsPerSec = ((double) threads * opsPerThread) / (elapsedNanos / 1_000_000_000.0);
            return new HeapAndReadResult(deltaHeap, opsPerSec);
        } finally {
            pool.shutdownNow();
        }
    }

    private static long settledUsedHeap() throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(80);
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static BenchResult runClientMatrix(
            int port, int connections, int pipeline, int totalOps,
            int keySpace, byte[] value64, boolean isSet) throws Exception {
        int opsPerConn = Math.max(pipeline, ((totalOps / connections) / pipeline) * pipeline);
        int actualTotal = opsPerConn * connections;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(connections, 200));
        CountDownLatch ready = new CountDownLatch(connections);
        CountDownLatch go = new CountDownLatch(1);
        long[] batchLatencies = new long[Math.min(actualTotal / pipeline, 40_000)];
        int sampleStride = Math.max(1, (actualTotal / pipeline) / batchLatencies.length);
        try {
            List<Future<long[]>> futures = new ArrayList<>(connections);
            for (int c = 0; c < connections; c++) {
                futures.add(pool.submit(() -> {
                    int batches = opsPerConn / pipeline;
                    long[] localSamples = new long[Math.max(1, batches / sampleStride + 1)];
                    int localIdx = 0;
                    try (Socket sock = new Socket("127.0.0.1", port)) {
                        sock.setTcpNoDelay(true);
                        BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream(), 32 * 1024);
                        BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), 32 * 1024);
                        ThreadLocalRandom rng = ThreadLocalRandom.current();
                        ready.countDown();
                        go.await();
                        for (int b = 0; b < batches; b++) {
                            boolean sample = (b % sampleStride == 0) && localIdx < localSamples.length;
                            long t0 = sample ? System.nanoTime() : 0L;
                            for (int p = 0; p < pipeline; p++) {
                                byte[] k = keyBytes(rng.nextInt(keySpace));
                                if (isSet) {
                                    writeSet(out, k, value64);
                                } else {
                                    writeGet(out, k);
                                }
                            }
                            out.flush();
                            for (int p = 0; p < pipeline; p++) {
                                readReply(in);
                            }
                            if (sample) {
                                localSamples[localIdx++] = System.nanoTime() - t0;
                            }
                        }
                    }
                    return Arrays.copyOf(localSamples, localIdx);
                }));
            }
            ready.await();
            long start = System.nanoTime();
            go.countDown();
            int pos = 0;
            for (Future<long[]> f : futures) {
                long[] part = f.get();
                int copy = Math.min(part.length, batchLatencies.length - pos);
                if (copy > 0) {
                    System.arraycopy(part, 0, batchLatencies, pos, copy);
                    pos += copy;
                }
            }
            long elapsed = System.nanoTime() - start;
            double rps = actualTotal / (elapsed / 1_000_000_000.0);
            Arrays.sort(batchLatencies, 0, pos);
            double p50 = pos > 0 ? batchLatencies[pos / 2] / 1_000_000.0 : 0.0;
            double p99 = pos > 0 ? batchLatencies[(int) (pos * 0.99)] / 1_000_000.0 : 0.0;
            return new BenchResult(rps, p50, p99);
        } finally {
            pool.shutdownNow();
        }
    }

    private static final byte[] GET_PREFIX = "*2\r\n$3\r\nGET\r\n$11\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SET_PREFIX = "*3\r\n$3\r\nSET\r\n$11\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VAL64_HDR = "\r\n$64\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = {'\r', '\n'};

    private static void writeGet(BufferedOutputStream out, byte[] key11) throws IOException {
        out.write(GET_PREFIX);
        out.write(key11);
        out.write(CRLF);
    }

    private static void writeSet(BufferedOutputStream out, byte[] key11, byte[] val64) throws IOException {
        out.write(SET_PREFIX);
        out.write(key11);
        out.write(VAL64_HDR);
        out.write(val64);
        out.write(CRLF);
    }

    private static void readReply(BufferedInputStream in) throws IOException {
        int prefix = in.read();
        if (prefix == '+' || prefix == '-' || prefix == ':') {
            skipLine(in);
            return;
        }
        if (prefix == '$') {
            int len = readLineInt(in);
            if (len >= 0) {
                int remaining = len + 2;
                while (remaining > 0) {
                    long skipped = in.skip(remaining);
                    if (skipped > 0) {
                        remaining -= (int) skipped;
                    } else if (in.read() == -1) {
                        throw new IOException("unexpected EOF in bulk payload");
                    } else {
                        remaining--;
                    }
                }
            }
            return;
        }
        throw new IOException("unexpected RESP prefix: " + prefix);
    }

    private static int readLineInt(BufferedInputStream in) throws IOException {
        int sign = 1;
        int val = 0;
        int b = in.read();
        if (b == '-') {
            sign = -1;
            b = in.read();
        }
        while (b != '\r') {
            if (b == -1) {
                throw new IOException("unexpected EOF in RESP integer");
            }
            val = val * 10 + (b - '0');
            b = in.read();
        }
        if (in.read() != '\n') {
            throw new IOException("expected LF after CR");
        }
        return sign * val;
    }

    private static void skipLine(BufferedInputStream in) throws IOException {
        int b;
        while ((b = in.read()) != '\n') {
            if (b == -1) {
                throw new IOException("unexpected EOF in RESP line");
            }
        }
    }

    private static byte[] keyBytes(int i) {
        byte[] key = new byte[11];
        key[0] = 'k';
        key[1] = 'e';
        key[2] = 'y';
        key[3] = ':';
        int v = i;
        for (int p = 10; p >= 4; p--) {
            key[p] = (byte) ('0' + (v % 10));
            v /= 10;
        }
        return key;
    }

    private static List<Embedding> randomEmbeddings(int count, int dim, long seed) {
        Random rng = new Random(seed);
        List<Embedding> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Embedding("bench-" + i, randomVec(rng, dim)));
        }
        return list;
    }

    private static float[] randomVec(Random rng, int dim) {
        float[] v = new float[dim];
        for (int d = 0; d < dim; d++) {
            v[d] = rng.nextFloat() * 2.0f - 1.0f;
        }
        return v;
    }

    private static void printNet(String label, BenchResult r) {
        System.out.printf(Locale.ROOT,
                "PATHB-NET-BENCH scenario=\"%s\" rps=%.0f p50=%.3fms p99=%.3fms%n",
                label, r.rps, r.p50Ms, r.p99Ms);
    }

    private record BenchResult(double rps, double p50Ms, double p99Ms) {
    }

    private record HeapAndReadResult(long liveHeapBytes, double concurrentReadOps) {
    }
}
